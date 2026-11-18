package com.vvvdroid.app

/**
 * JNI bridge to the Firebird C API (libfbclient.so).
 *
 * Catalogs are attached with read-only transactions, so no garbage
 * collection or record-version writes are triggered by us.
 */
object CatalogDb {

    data class Volume(
        val id: Long,
        val name: String,
        val description: String?,
        val physicalPath: String?,
        val catalogDate: String?,
        val lastUpdateDate: String?,
    )

    /** One row in a folder listing. */
    data class Entry(
        val id: Long,
        val name: String,
        val isFolder: Boolean,
        val size: Long?,
        val date: String?,
        val ext: String?,
    )

    init {
        System.loadLibrary("vvvdroid")
    }

    /** Opens a catalog read-only. Throws IllegalStateException on failure. */
    external fun openCatalog(dbPath: String, firebirdHome: String): Long

    external fun closeCatalog(handle: Long)

    /** Returns all volumes; each row = [id, name, description, physicalPath, catalogDate, lastUpdateDate]. */
    external fun listVolumes(handle: Long): Array<Array<String?>>

    /** Returns the DB_VERSION of the catalog (VVV 2.0 currently: "23"). */
    external fun dbVersion(handle: Long): String

    /**
     * True when the catalog's header page carries the persistent read-only
     * flag (hdr_read_only): the engine then provably writes nothing at all.
     * False = read-write catalog: opening it may modify the file
     * (transaction-inventory updates, garbage collection) and file syncers
     * would re-transfer it. A catalog can be flagged read-only once on the
     * PC, e.g. `gfix -mode read_only catalog.vvv`.
     */
    external fun dbReadOnly(handle: Long): Boolean

    /**
     * Header page of a catalog file, read directly (no engine attach).
     *
     * Needed because attaching a read-write catalog already modifies it: the
     * read-only flag has to be known *before* deciding how to open the file.
     */
    data class HeaderInfo(
        val flags: Int,
        val pageSize: Int,
        val odsMajor: Int,
        val odsMinor: Int,
    ) {
        /** hdr_read_only (0x20): the engine provably writes nothing at all. */
        val readOnly: Boolean get() = (flags and 0x20) != 0

        val format: String get() = "ODS $odsMajor.$odsMinor"
    }

    private external fun nativeHeader(path: String): IntArray

    /**
     * Returns the header info when [path] is a Firebird database, null when
     * the file is missing, unreadable, or not a database.
     */
    fun header(path: String): HeaderInfo? {
        val v = nativeHeader(path)
        if (v.size < 5 || v[0] != 1) return null
        return HeaderInfo(flags = v[1], pageSize = v[2], odsMajor = v[3], odsMinor = v[4])
    }

    /**
     * Probe which file operations the filesystem behind [path] supports.
     * Used to find out whether shared storage (FUSE) accepts the file
     * locking calls the engine needs. Returns a short diagnostic string.
     */
    external fun probeFileLock(path: String): String

    fun volumes(handle: Long): List<Volume> =
        listVolumes(handle).map { row ->
            Volume(
                id = row[0]?.toLongOrNull() ?: 0L,
                name = row[1].orEmpty(),
                description = row[2],
                physicalPath = row[3],
                catalogDate = row[4],
                lastUpdateDate = row[5],
            )
        }

    private external fun nativeQuery(handle: Long, sql: String): Array<Array<String?>>

    /** Generic string query (see nativeQuery); SQL is built only from ids. */
    fun query(handle: Long, sql: String): List<List<String?>> =
        nativeQuery(handle, sql).map { it.toList() }

    /** PATH_ID of a volume's synthetic root folder (empty name, no parent). */
    fun rootPathId(handle: Long, volumeId: Long): Long =
        query(
            handle,
            "SELECT CAST(PATH_ID AS VARCHAR(20)) FROM PATHS " +
                    "WHERE VOLUME_ID = $volumeId AND FATHER_ID IS NULL"
        ).first()[0]!!.toLong()

    /**
     * Contents of one folder: sub-folders first, then files.
     *
     * Every folder also has a row in FILES whose PATH_FILE_ID points to its
     * own PATHS.PATH_ID, so those rows are excluded from the file query;
     * folder names come from PATHS (the authoritative tree).
     */
    fun entries(handle: Long, volumeId: Long, parentId: Long): List<Entry> {
        val folders = query(
            handle,
            "SELECT CAST(PATH_ID AS VARCHAR(20)), CAST(PATH_NAME AS VARCHAR(1000)) " +
                    "FROM PATHS WHERE VOLUME_ID = $volumeId AND FATHER_ID = $parentId " +
                    "ORDER BY UPPER(PATH_NAME)"
        ).map { Entry(it[0]!!.toLong(), it[1].orEmpty(), true, null, null, null) }

        val files = query(
            handle,
            "SELECT CAST(FILE_ID AS VARCHAR(20)), CAST(FILE_NAME AS VARCHAR(1000)), " +
                    "CAST(FILE_SIZE AS VARCHAR(20)), CAST(FILE_DATETIME AS VARCHAR(32)), FILE_EXT " +
                    "FROM FILES WHERE PATH_ID = $parentId AND PATH_FILE_ID IS NULL " +
                    "ORDER BY UPPER(FILE_NAME)"
        ).map {
            Entry(
                id = it[0]!!.toLong(),
                name = it[1].orEmpty(),
                isFolder = false,
                size = it[2]?.toLongOrNull(),
                date = it[3]?.take(19),
                ext = it[4]?.takeIf { e -> e.isNotBlank() },
            )
        }
        return folders + files
    }
}
