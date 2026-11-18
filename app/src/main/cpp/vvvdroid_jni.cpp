#include <jni.h>
#include <ibase.h>

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <string>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <vector>

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

namespace {

struct CatalogHandle {
    // FB_API_HANDLE is `unsigned int` on this platform (not a pointer)
    isc_db_handle db = 0;
    isc_tr_handle tr = 0;
};

std::string fbErrorText(ISC_STATUS* status) {
    std::string msg;
    const ISC_STATUS* p = status;
    char buf[512];
    while (fb_interpret(buf, sizeof(buf), &p)) {
        if (!msg.empty()) msg += "; ";
        msg += buf;
    }
    if (msg.empty()) msg = "unknown Firebird error";
    return msg;
}

void throwJava(JNIEnv* env, const std::string& msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, msg.c_str());
}

void putDpbItem(char*& p, unsigned char item, const char* value, unsigned char len) {
    *p++ = static_cast<char>(item);
    *p++ = static_cast<char>(len);
    std::memcpy(p, value, len);
    p += len;
}

// Returns the string content of a fetched SQL_VARYING/SQL_TEXT column,
// or nullptr when the value is NULL.
jstring fetchedString(JNIEnv* env, const XSQLDA* sqlda, int i) {
    const XSQLVAR& var = sqlda->sqlvar[i];
    if (var.sqlind != nullptr && *var.sqlind < 0) return nullptr;
    // SQL_VARYING: 2-byte little-endian length prefix, then raw UTF-8 bytes;
    // CHAR (mask off the +1 "nullable" bit of sqltype) has no prefix
    bool isChar = (var.sqltype & ~1) == SQL_TEXT;
    unsigned short len = isChar ? var.sqllen
        : static_cast<unsigned short>(static_cast<unsigned char>(var.sqldata[0]) |
            (static_cast<unsigned short>(static_cast<unsigned char>(var.sqldata[1])) << 8));
    const char* data = var.sqldata + (isChar ? 0 : 2);
    return env->NewStringUTF(std::string(data, len).c_str());
}

// Runs a query whose result columns are all character types and returns the
// rows as String[][]; NULL values become Java null. Column buffers are bound
// as SQL_VARYING with the NULL indicator enabled (the +1 flag).
jobjectArray runStringQuery(JNIEnv* env, CatalogHandle* h, const char* sql,
                            const char* label) {
    ISC_STATUS_ARRAY status;
    isc_stmt_handle stmt = 0;

    // stub to learn the column count
    XSQLDA* sqlda = static_cast<XSQLDA*>(malloc(XSQLDA_LENGTH(1)));
    sqlda->sqln = 1;
    sqlda->version = 1;

    if (isc_dsql_allocate_statement(status, &h->db, &stmt) ||
        isc_dsql_prepare(status, &h->tr, &stmt, 0, const_cast<char*>(sql), 3, sqlda)) {
        std::string err = fbErrorText(status);
        free(sqlda);
        throwJava(env, std::string(label) + ": " + err);
        return nullptr;
    }

    // reallocate for the real column count and describe
    if (sqlda->sqld > sqlda->sqln) {
        int cols = sqlda->sqld;
        free(sqlda);
        sqlda = static_cast<XSQLDA*>(malloc(XSQLDA_LENGTH(cols)));
        sqlda->sqln = cols;
        sqlda->version = 1;
        if (isc_dsql_describe(status, &stmt, 3, sqlda)) {
            std::string err = fbErrorText(status);
            free(sqlda);
            throwJava(env, std::string(label) + ": " + err);
            return nullptr;
        }
    }

    const short cols = sqlda->sqld;
    for (short i = 0; i < cols; ++i) {
        XSQLVAR& var = sqlda->sqlvar[i];
        var.sqltype = SQL_VARYING + 1; // +1 = fill the NULL indicator
        var.sqldata = static_cast<char*>(malloc(var.sqllen + 2));
        var.sqlind = static_cast<short*>(malloc(sizeof(short)));
    }

    std::vector<std::vector<std::string>> rows;
    std::string execError;
    if (isc_dsql_execute(status, &h->tr, &stmt, 3, nullptr) == 0) {
        while (isc_dsql_fetch(status, &stmt, 3, sqlda) == 0) {
            std::vector<std::string> row;
            for (short i = 0; i < cols; ++i) {
                const XSQLVAR& var = sqlda->sqlvar[i];
                if (var.sqlind != nullptr && *var.sqlind < 0) {
                    row.push_back("");
                    continue;
                }
                unsigned short len = static_cast<unsigned char>(var.sqldata[0]);
                len |= static_cast<unsigned short>(static_cast<unsigned char>(var.sqldata[1])) << 8;
                row.push_back(std::string(var.sqldata + 2, len));
            }
            rows.push_back(std::move(row));
        }
    } else {
        execError = fbErrorText(status);
    }

    for (short i = 0; i < cols; ++i) {
        free(sqlda->sqlvar[i].sqldata);
        free(sqlda->sqlvar[i].sqlind);
    }
    free(sqlda);
    isc_dsql_free_statement(status, &stmt, DSQL_drop);

    if (!execError.empty()) {
        throwJava(env, std::string(label) + ": " + execError);
        return nullptr;
    }

    jclass strArrayCls = env->FindClass("[Ljava/lang/String;");
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(rows.size()), strArrayCls, nullptr);

    for (jsize r = 0; r < static_cast<jsize>(rows.size()); ++r) {
        jobjectArray rowArr = env->NewObjectArray(cols, env->FindClass("java/lang/String"), nullptr);
        for (short c = 0; c < cols; ++c) {
            jstring s = env->NewStringUTF(rows[r][c].c_str());
            env->SetObjectArrayElement(rowArr, c, s);
            env->DeleteLocalRef(s);
        }
        env->SetObjectArrayElement(result, r, rowArr);
        env->DeleteLocalRef(rowArr);
    }
    return result;
}

} // namespace

extern "C" {

// ---------------------------------------------------------------------------
// openCatalog(dbPath, firebirdHome) -> handle
//
// Attaches the catalog with a read-only transaction (isc_tpb_read_only), so
// no garbage collection or record-version writes are triggered by us.
//
// IMPORTANT: Firebird has no per-connection read-only attach. A database is
// provably immutable (engine writes nothing at all) only when its header page
// carries the persistent read-only flag (hdr_read_only, set once e.g. with
// "gfix -mode read_only"). dbReadOnly() below reports that state so the app
// can warn when the catalog is read-write and may be modified on open.
// ---------------------------------------------------------------------------
JNIEXPORT jlong JNICALL
Java_com_vvvdroid_app_CatalogDb_openCatalog(JNIEnv* env, jclass, jstring jDbPath,
                                            jstring jFirebirdHome) {
    const char* dbPath = env->GetStringUTFChars(jDbPath, nullptr);
    const char* fbHome = env->GetStringUTFChars(jFirebirdHome, nullptr);

    // The embedded engine finds its conf/msg/tzdata files via FIREBIRD.
    // The lock manager and temporary storage need writable directories of
    // their own — on Android the compiled-in default (/data/local/tmp) is
    // inaccessible, so point both into the home dir (same layout the AAR's
    // own helper uses: <home>/tmp, <home>/lock).
    setenv("FIREBIRD", fbHome, 1);
    std::string tmpDir = std::string(fbHome) + "/tmp";
    std::string lockDir = std::string(fbHome) + "/lock";
    mkdir(tmpDir.c_str(), 0755);
    mkdir(lockDir.c_str(), 0755);
    setenv("FIREBIRD_TMP", tmpDir.c_str(), 1);
    setenv("FIREBIRD_LOCK", lockDir.c_str(), 1);

    char dpb[256];
    char* p = dpb;
    *p++ = isc_dpb_version1;
    putDpbItem(p, isc_dpb_utf8_filename, "\x01", 1);
    putDpbItem(p, isc_dpb_lc_ctype, "UTF8", 4);
    putDpbItem(p, isc_dpb_user_name, "SYSDBA", 6);

    ISC_STATUS_ARRAY status;
    auto* h = new CatalogHandle();
    if (isc_attach_database(status, 0, const_cast<char*>(dbPath), &h->db,
                            static_cast<short>(p - dpb), dpb)) {
        std::string err = fbErrorText(status);
        delete h;
        env->ReleaseStringUTFChars(jDbPath, dbPath);
        env->ReleaseStringUTFChars(jFirebirdHome, fbHome);
        throwJava(env, "attach failed: " + err);
        return 0;
    }

    // isc_tpb_read = read-only transaction (there is no isc_tpb_read_only)
    static const char tpb[] = {isc_tpb_version1, isc_tpb_read,
                               isc_tpb_concurrency, isc_tpb_wait};
    if (isc_start_transaction(status, &h->tr, 1, &h->db, sizeof(tpb), tpb)) {
        std::string err = fbErrorText(status);
        ISC_STATUS_ARRAY st2;
        isc_detach_database(st2, &h->db);
        delete h;
        env->ReleaseStringUTFChars(jDbPath, dbPath);
        env->ReleaseStringUTFChars(jFirebirdHome, fbHome);
        throwJava(env, "start transaction failed: " + err);
        return 0;
    }

    env->ReleaseStringUTFChars(jDbPath, dbPath);
    env->ReleaseStringUTFChars(jFirebirdHome, fbHome);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(h));
}

// ---------------------------------------------------------------------------
// dbVersion(handle) -> "24" (ODS 13 schema version)
// ---------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_vvvdroid_app_CatalogDb_dbVersion(JNIEnv* env, jclass, jlong jHandle) {
    auto* h = reinterpret_cast<CatalogHandle*>(static_cast<intptr_t>(jHandle));
    if (h == nullptr || h->db == 0) {
        throwJava(env, "catalog not open");
        return nullptr;
    }

    static const char* SQL =
        "SELECT CAST(DB_VERSION AS VARCHAR(8)) FROM SERVICE WHERE SERVICE_ID = 1";

    ISC_STATUS_ARRAY status;
    isc_stmt_handle stmt = 0;

    XSQLDA* sqlda = static_cast<XSQLDA*>(malloc(XSQLDA_LENGTH(1)));
    sqlda->sqln = 1;
    sqlda->version = 1;

    if (isc_dsql_allocate_statement(status, &h->db, &stmt) ||
        isc_dsql_prepare(status, &h->tr, &stmt, 0, const_cast<char*>(SQL), 3, sqlda)) {
        std::string err = fbErrorText(status);
        free(sqlda);
        throwJava(env, "dbVersion: " + err);
        return nullptr;
    }

    // single VARCHAR column -> allocate the buffer, add the NULL indicator
    XSQLVAR& var = sqlda->sqlvar[0];
    var.sqltype = SQL_VARYING + 1; // +1 = fill the NULL indicator
    var.sqldata = static_cast<char*>(malloc(var.sqllen + 2));
    var.sqlind = static_cast<short*>(malloc(sizeof(short)));

    jstring result = nullptr;
    if (isc_dsql_execute(status, &h->tr, &stmt, 3, nullptr) ||
        isc_dsql_fetch(status, &stmt, 3, sqlda)) {
        std::string err = fbErrorText(status);
        // 100 = end of stream (no row) -> empty catalog, not an error
        if (status[1] != 100) {
            free(var.sqldata); free(var.sqlind); free(sqlda);
            throwJava(env, "dbVersion: " + err);
            return nullptr;
        }
    } else {
        result = fetchedString(env, sqlda, 0);
    }

    free(var.sqldata);
    free(var.sqlind);
    free(sqlda);
    isc_dsql_free_statement(status, &stmt, DSQL_drop);
    return result;
}

// ---------------------------------------------------------------------------
// listVolumes(handle) -> Array<Array<String?>>
// row = [id, name, description, physicalPath, catalogDate, lastUpdateDate]
// ---------------------------------------------------------------------------
JNIEXPORT jobjectArray JNICALL
Java_com_vvvdroid_app_CatalogDb_listVolumes(JNIEnv* env, jclass, jlong jHandle) {
    auto* h = reinterpret_cast<CatalogHandle*>(static_cast<intptr_t>(jHandle));
    if (h == nullptr || h->db == 0) {
        throwJava(env, "catalog not open");
        return nullptr;
    }

    // Everything is fetched as VARCHAR so one uniform code path handles all
    // columns (the desktop UI does the same formatting).
    static const char* SQL =
        "SELECT CAST(VOLUME_ID AS VARCHAR(20)), VOLUME_NAME, VOLUME_DESCRIPTION, "
        "PHYSICAL_PATH, CAST(CATALOG_DATE AS VARCHAR(32)), "
        "CAST(LAST_UPDATE_DATE AS VARCHAR(32)) "
        "FROM VOLUMES ORDER BY UPPER(VOLUME_NAME)";

    return runStringQuery(env, h, SQL, "listVolumes");
}

// ---------------------------------------------------------------------------
// nativeQuery(handle, sql) -> Array<Array<String?>>
//
// Generic string-only query used for browsing. Every selected column must be
// a character type (CAST numbers/timestamps); NULL values come back as Java
// null. SQL is built in Kotlin from database-supplied numeric ids only.
// ---------------------------------------------------------------------------
JNIEXPORT jobjectArray JNICALL
Java_com_vvvdroid_app_CatalogDb_nativeQuery(JNIEnv* env, jclass, jlong jHandle, jstring jSql) {
    auto* h = reinterpret_cast<CatalogHandle*>(static_cast<intptr_t>(jHandle));
    if (h == nullptr || h->db == 0) {
        throwJava(env, "catalog not open");
        return nullptr;
    }

    const char* sql = env->GetStringUTFChars(jSql, nullptr);
    jobjectArray result = runStringQuery(env, h, sql, "query");
    env->ReleaseStringUTFChars(jSql, sql);
    return result;
}

// ---------------------------------------------------------------------------
// dbReadOnly(handle) -> true when the catalog's header page carries the
// persistent read-only flag (hdr_read_only) => the engine provably writes
// nothing. false = read-write database: opening it may modify the file.
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_vvvdroid_app_CatalogDb_dbReadOnly(JNIEnv* env, jclass, jlong jHandle) {
    auto* h = reinterpret_cast<CatalogHandle*>(static_cast<intptr_t>(jHandle));
    if (h == nullptr || h->db == 0) {
        throwJava(env, "catalog not open");
        return JNI_FALSE;
    }

    static const char request[] = {isc_info_db_read_only, isc_info_end};
    char buffer[16];
    ISC_STATUS_ARRAY status;
    if (isc_database_info(status, &h->db, sizeof(request), request,
                          sizeof(buffer), buffer)) {
        throwJava(env, "dbReadOnly: " + fbErrorText(status));
        return JNI_FALSE;
    }

    // one info item: <byte item> <2-byte big-endian length> <value bytes>
    if (buffer[0] == isc_info_db_read_only) {
        const int len = (static_cast<unsigned char>(buffer[1]) << 8) |
                        static_cast<unsigned char>(buffer[2]);
        if (len >= 1)
            return buffer[3] ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

// ---------------------------------------------------------------------------
// nativeHeader(path) -> int[5] = {pagType, hdrFlags, pageSize, odsMajor, odsMinor}
//
// Reads the database header page directly, without attaching an engine. This
// is what makes a safe open decision possible: attaching an RW catalog already
// modifies it, so the read-only flag has to be inspected before any attach.
//
// Layout (Firebird ODS 13, src/jrd/ods.h, offsetof asserts):
//   0   pag_type        (1 = pag_header)
//   16  hdr_page_size
//   18  hdr_ods_version (low byte = major)
//   42  hdr_flags       (0x20 = hdr_read_only, 0x02 = force write,
//                        0x10 = SQL dialect 3)
//   64  hdr_ods_minor
// The header page is stored in little-endian byte order on the ABIs we ship.
// Returns pagType = -1 when the file is missing/unreadable/not a database.
// ---------------------------------------------------------------------------
JNIEXPORT jintArray JNICALL
Java_com_vvvdroid_app_CatalogDb_nativeHeader(JNIEnv* env, jclass, jstring jPath) {
    const char* path = env->GetStringUTFChars(jPath, nullptr);

    unsigned char buf[132];
    int n = -1;
    int fd = open(path, O_RDONLY);
    if (fd >= 0) {
        n = static_cast<int>(pread(fd, buf, sizeof(buf), 0));
        close(fd);
    }
    env->ReleaseStringUTFChars(jPath, path);

    jint vals[5] = {-1, 0, 0, 0, 0};
    // 66 bytes cover everything up to hdr_end; a valid header page always
    // carries at least that much data.
    if (n >= 66 && buf[0] == 1) {
        const unsigned short flags    = static_cast<unsigned short>(buf[42] | (buf[43] << 8));
        const unsigned short pageSize = static_cast<unsigned short>(buf[16] | (buf[17] << 8));
        const unsigned short ods      = static_cast<unsigned short>(buf[18] | (buf[19] << 8));
        const unsigned short odsMinor = static_cast<unsigned short>(buf[64] | (buf[65] << 8));
        if (pageSize >= 4096 && pageSize <= 32768 && (pageSize & (pageSize - 1)) == 0) {
            vals[0] = 1;
            vals[1] = flags;
            vals[2] = pageSize;
            vals[3] = ods & 0xFF;
            vals[4] = odsMinor & 0x7F;
        }
    }

    jintArray out = env->NewIntArray(5);
    env->SetIntArrayRegion(out, 0, 5, vals);
    return out;
}

// ---------------------------------------------------------------------------
// closeCatalog(handle)
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_vvvdroid_app_CatalogDb_closeCatalog(JNIEnv* env, jclass, jlong jHandle) {
    auto* h = reinterpret_cast<CatalogHandle*>(static_cast<intptr_t>(jHandle));
    if (h == nullptr) return;
    ISC_STATUS_ARRAY status;
    if (h->tr != 0) isc_rollback_transaction(status, &h->tr);
    if (h->db != 0) isc_detach_database(status, &h->db);
    delete h;
}

// ---------------------------------------------------------------------------
// probeFileLock(path) -> diagnostics string. Tries the operations the engine
// performs on a database file (open, fcntl lock, flock, pread, mmap) and
// reports which ones the filesystem supports. Shared storage (FUSE) on
// Android typically rejects the locking calls, which makes attach fail with
// "I/O error ... lock ... Function not implemented".
// ---------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_vvvdroid_app_CatalogDb_probeFileLock(JNIEnv* env, jclass, jstring jPath) {
    const char* path = env->GetStringUTFChars(jPath, nullptr);

    std::string result;
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        result = std::string("open: ") + strerror(errno);
        env->ReleaseStringUTFChars(jPath, path);
        return env->NewStringUTF(result.c_str());
    }

    struct flock lck;
    memset(&lck, 0, sizeof(lck));
    lck.l_type = F_RDLCK;
    lck.l_whence = SEEK_SET;
    lck.l_start = 0;
    lck.l_len = 4096;
    if (fcntl(fd, F_SETLK, &lck) == 0) result += "fcntl=OK";
    else result += std::string("fcntl=") + strerror(errno);
    lck.l_type = F_UNLCK;
    fcntl(fd, F_SETLK, &lck);

    if (flock(fd, LOCK_SH | LOCK_NB) == 0) result += " flock=OK";
    else result += std::string(" flock=") + strerror(errno);
    flock(fd, LOCK_UN);

    char buf[16];
    if (pread(fd, buf, sizeof(buf), 0) >= 0) result += " pread=OK";
    else result += std::string(" pread=") + strerror(errno);

    void* m = mmap(nullptr, 4096, PROT_READ, MAP_PRIVATE, fd, 0);
    if (m != MAP_FAILED) {
        result += " mmap=OK";
        munmap(m, 4096);
    } else {
        result += std::string(" mmap=") + strerror(errno);
    }

    close(fd);
    env->ReleaseStringUTFChars(jPath, path);
    return env->NewStringUTF(result.c_str());
}

} // extern "C"
