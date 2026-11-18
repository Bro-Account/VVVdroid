package com.vvvdroid.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A cold start may find a private copy left behind by a killed process;
        // closing a catalog deletes it, this catches the unclean exit.
        // A private catalog copy can only outlive the UI state through an
        // unclean exit (process kill, or the system recreating the activity):
        // rotation and theme changes are handled in-place (configChanges), so
        // onCreate always means no catalog is attached and any leftover copy
        // is orphaned. Deliberate closing deletes the copy immediately.
        run {
            val orphans = File(filesDir, "catalogs").listFiles() ?: return@run
            for (f in orphans) if (f.name == "private-copy.vvv") f.delete()
        }
        // Debug/automation entry points (used by scripts/emulator-test.ps1):
        //   adb shell am start -n com.vvvdroid.app/.MainActivity \
        //       -e vvv.catalog /sdcard/Download/x.vvv -e vvv.mode auto
        // modes: auto (ask when read-write), copy (always copy), inplace (never ask)
        setContent {
            App(
                activity = this,
                autoPath = intent?.getStringExtra(EXTRA_CATALOG),
                autoMode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_AUTO,
            )
        }
    }

    companion object {
        const val EXTRA_CATALOG = "vvv.catalog"
        const val EXTRA_MODE = "vvv.mode"
        const val MODE_AUTO = "auto"
        const val MODE_COPY = "copy"
        const val MODE_INPLACE = "inplace"
    }
}

/** One level of the browse history (one screen per depth, KeePassDroid style). */
private sealed class Crumb(val title: String) {
    class Volume(val id: Long, val rootId: Long, title: String) : Crumb(title)
    class Folder(val volumeId: Long, val id: Long, title: String) : Crumb(title)
}

/** Whether the app may read arbitrary files on shared storage. */
private fun hasStorageAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/** Opens the "All files access" settings screen for this app; falls back to
 *  the global list screen when the direct route is unavailable (some OEMs). */
private fun requestStorageAccess(activity: Activity): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val direct = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        try {
            activity.startActivity(direct)
            return true
        } catch (_: Exception) {
            // fall through to the global screen
        }
        val global = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        try {
            activity.startActivity(global)
            return true
        } catch (_: Exception) {
            // fall through to the app details screen
        }
    }
    val appDetails = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${activity.packageName}")
    )
    return try {
        activity.startActivity(appDetails)
        true
    } catch (_: Exception) {
        false
    }
}

/** Copies the Firebird runtime assets (conf, msg, tzdata) from the AAR to a
 *  private dir and points the FIREBIRD env var there (done once at startup). */
private fun ensureFirebirdHome(context: Context): String {
    val dst = File(context.filesDir, "firebird")
    if (!dst.exists()) {
        dst.mkdirs()
        context.assets.list("firebird")?.forEach { name ->
            context.assets.open("firebird/$name").use { input ->
                File(dst, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
    return dst.absolutePath
}

/** Resolves the real filesystem path behind a content:// URI for the
 *  common local storage providers (DocumentsUI / externalstorage). */
private fun pathFromUri(context: Context, uri: Uri): String? {
    return try {
        val docId = DocumentsContract.getDocumentId(uri) ?: return null
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return null
        when (parts[0]) {
            "primary" -> "/storage/emulated/0/${parts[1]}"
            "home" -> "/storage/emulated/0/${parts[1]}"
            else -> null // external SD cards etc.: keep manual entry for now
        }
    } catch (_: Exception) {
        null
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}

/** Copies [source] to [dest], reporting progress at most every 32 MB. */
private fun copyWithProgress(
    source: File,
    dest: File,
    onProgress: (copied: Long, total: Long) -> Unit,
) {
    dest.parentFile?.mkdirs()
    val total = source.length()
    var copied = 0L
    var lastReport = 0L
    source.inputStream().use { input ->
        dest.outputStream().use { output ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                copied += n
                if (copied - lastReport >= 32L shl 20) {
                    lastReport = copied
                    onProgress(copied, total)
                }
            }
        }
    }
    onProgress(copied, total)
}

@Composable
private fun App(activity: Activity, autoPath: String?, autoMode: String) {
    var hasAllFiles by remember { mutableStateOf(hasStorageAccess(activity)) }
    var dbPath by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf("No catalog open.") }
    var volumes by remember { mutableStateOf<List<CatalogDb.Volume>>(emptyList()) }

    // read-write catalog waiting for the user's decision
    var rwPromptPath by remember { mutableStateOf<String?>(null) }
    var copying by remember { mutableStateOf(false) }
    var copyPercent by remember { mutableStateOf(0) }

    // path of the private copy currently attached (deleted on close)
    var openCopyPath by remember { mutableStateOf<String?>(null) }

    // browse state: one crumb per screen depth
    var crumbs by remember { mutableStateOf<List<Crumb>>(emptyList()) }
    var entries by remember { mutableStateOf<List<CatalogDb.Entry>>(emptyList()) }
    var entriesLoading by remember { mutableStateOf(false) }
    var fileInfo by remember { mutableStateOf<CatalogDb.Entry?>(null) }

    // Re-check the permission whenever the app comes back to the foreground:
    // the Settings screens are launched with plain startActivity (no result),
    // so returning from them only surfaces as an ON_RESUME here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasStorageAccess(activity)
                if (granted != hasAllFiles) {
                    hasAllFiles = granted
                    status = if (granted) "Storage access granted."
                    else "Storage access was revoked — \"All files access\" is " +
                            "required to open catalog files where they are stored."
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pickCatalog = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val resolved = pathFromUri(activity, uri)
            dbPath = resolved ?: uri.toString()
            status = if (resolved == null)
                "Could not resolve a file path for the selection — please enter " +
                        "the full path of the .vvv file manually below."
            else "Catalog selected: $resolved"
        }
    }

    fun resetBrowse() {
        crumbs = emptyList()
        entries = emptyList()
        fileInfo = null
    }

    /** Closes the catalog; a private copy is deleted. Returns true if removed. */
    fun closeOpen(): Boolean {
        var deletedCopy = false
        handle?.let { h -> try { CatalogDb.closeCatalog(h) } catch (_: Throwable) {} }
        handle = null
        volumes = emptyList()

        val copy = openCopyPath
        if (copy != null) {
            val f = File(copy)
            if (f.exists()) deletedCopy = f.delete()
            openCopyPath = null
            if (dbPath == copy) dbPath = ""
        }
        resetBrowse()
        return deletedCopy
    }

    fun openInPlace(path: String, privateCopy: Boolean) {
        try {
            val fbHome = ensureFirebirdHome(activity)
            handle = CatalogDb.openCatalog(path, fbHome)
            val ver = CatalogDb.dbVersion(handle!!)
            val readOnly = CatalogDb.dbReadOnly(handle!!)
            volumes = CatalogDb.volumes(handle!!)
            openCopyPath = if (privateCopy) path else null
            resetBrowse()
            status = when {
                readOnly ->
                    "Open. Schema version $ver — catalog is flagged read-only; " +
                            "the file is never modified."
                privateCopy ->
                    "Open (private copy). Schema version $ver — the original " +
                            "catalog is untouched; the copy is deleted on close."
                else ->
                    "Open. Schema version $ver — WARNING: read-write catalog opened " +
                            "in place; the engine may modify it and trigger a re-sync."
            }
        } catch (e: Throwable) {
            handle = null
            volumes = emptyList()
            status = "Open failed: ${e.message}"
        }
    }

    fun beginCopy(path: String) {
        val src = File(path)
        val dest = File(File(activity.filesDir, "catalogs"), "private-copy.vvv")
        dest.parentFile?.mkdirs()
        val need = src.length() + (16L shl 20)
        if (dest.parentFile!!.usableSpace < need) {
            status = "Not enough free space in app storage: " +
                    "${formatSize(need)} needed, " +
                    "${formatSize(dest.parentFile!!.usableSpace)} available."
            return
        }

        closeOpen()
        if (dest.exists() && !dest.delete()) {
            status = "Could not replace the previous private copy in app storage."
            return
        }

        copying = true
        copyPercent = 0
        status = "Copying ${formatSize(src.length())} to app storage… this copy " +
                "is deleted when you close the catalog."
        Thread {
            try {
                copyWithProgress(src, dest) { done, total ->
                    val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                    activity.runOnUiThread {
                        copyPercent = pct
                        status = "Copying to app storage… $pct%"
                    }
                }
                activity.runOnUiThread {
                    copying = false
                    dbPath = dest.absolutePath
                    openInPlace(dest.absolutePath, privateCopy = true)
                }
            } catch (e: Throwable) {
                activity.runOnUiThread {
                    copying = false
                    status = "Copy failed: ${e.message}"
                }
            }
        }.start()
    }

    /** Decides how to open [path] *without* attaching first (an attach itself
     *  writes to a read-write catalog). */
    fun startOpen(path: String, mode: String) {
        if (copying) {
            status = "A copy is still in progress — please wait."
            return
        }
        val file = File(path)
        if (!file.exists()) {
            status = "File not found: $path"
            return
        }
        val header = try { CatalogDb.header(path) } catch (_: Throwable) { null }
        if (header == null) {
            status = "Not a Firebird database (or not readable): $path"
            return
        }
        if (header.odsMajor < 13) {
            status = "This catalog uses the legacy ${header.format} format (VVV 1.5). " +
                    "Convert it on the PC with the catalog converter first."
            return
        }
        if (header.readOnly || mode == MainActivity.MODE_INPLACE) {
            openInPlace(path, privateCopy = false)
            return
        }
        if (mode == MainActivity.MODE_COPY) {
            beginCopy(path)
            return
        }
        status = "Catalog is read-write (${header.format}) — choose how to open it."
        rwPromptPath = path
    }

    // One-shot auto-open for scripts: the activity is recreated on rotation,
    // so remember that the launch intent has already been consumed.
    var autoConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoPath, autoMode) {
        if (!autoPath.isNullOrBlank() && !autoConsumed) {
            autoConsumed = true
            dbPath = autoPath
            startOpen(autoPath, autoMode)
        }
    }

    // Folder contents of the current screen, loaded off the UI thread.
    LaunchedEffect(handle, crumbs) {
        val h = handle
        val crumb = crumbs.lastOrNull()
        if (h == null || crumb == null) {
            entries = emptyList()
            return@LaunchedEffect
        }
        entriesLoading = true
        entries = emptyList()
        val loaded = try {
            withContext(Dispatchers.IO) {
                when (crumb) {
                    is Crumb.Volume -> CatalogDb.entries(h, crumb.id, crumb.rootId)
                    is Crumb.Folder -> CatalogDb.entries(h, crumb.volumeId, crumb.id)
                }
            }
        } catch (e: Throwable) {
            activity.runOnUiThread { status = "Listing failed: ${e.message}" }
            null
        }
        entriesLoading = false
        if (loaded != null) entries = loaded
    }

    BackHandler(enabled = crumbs.isNotEmpty()) {
        crumbs = crumbs.dropLast(1)
    }

    MaterialTheme {
        if (crumbs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "VVVdroid " + try {
                        activity.packageManager
                            .getPackageInfo(activity.packageName, 0).versionName
                    } catch (_: Exception) { "" },
                    fontSize = 24.sp, fontWeight = FontWeight.Bold,
                )

                if (!hasAllFiles) {
                    Button(onClick = {
                        if (!requestStorageAccess(activity)) {
                            status = "Could not open the settings screen. Grant access " +
                                    "manually: Settings → search \"All files access\"."
                        }
                    }) { Text("Grant storage access (All files)") }
                    Text(
                        "Catalog files are opened directly where they are stored. " +
                                "\"All files access\" lets the app read them in place; " +
                                "read-only catalogs are never modified. A read-write " +
                                "catalog is opened as a private copy by default."
                    )
                }

                OutlinedTextField(
                    value = dbPath,
                    onValueChange = { dbPath = it },
                    label = { Text("Catalog file (.vvv)") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        pickCatalog.launch(arrayOf("*/*"))
                    }) { Text("Pick…") }

                    Button(
                        enabled = hasAllFiles && dbPath.isNotBlank() && handle == null && !copying,
                        onClick = { startOpen(dbPath.trim(), MainActivity.MODE_AUTO) },
                    ) { Text("Open") }

                    if (handle != null) {
                        Button(onClick = {
                            val removed = closeOpen()
                            status = if (removed) "Catalog closed — private copy deleted."
                            else "Catalog closed."
                        }) { Text("Close") }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val p = dbPath.trim()
                        if (p.isBlank()) {
                            status = "Select a catalog path first."
                            return@OutlinedButton
                        }
                        val probe = StringBuilder()
                        probe.append("Header — ")
                        val h = try { CatalogDb.header(p) } catch (_: Throwable) { null }
                        if (h == null) {
                            probe.append("not a Firebird database")
                        } else {
                            probe.append(h.format)
                            probe.append(", page size ").append(h.pageSize)
                            probe.append(", flags 0x").append("%02x".format(h.flags))
                            probe.append(if (h.readOnly) ", read-only" else ", read-write")
                        }
                        probe.append("  |  Shared storage — ").append(CatalogDb.probeFileLock(p))
                        val tmp = File(activity.filesDir, "probe.tmp")
                        tmp.writeText("0123456789abcdef0123456789abcdef")
                        probe.append("  |  App storage — ").append(CatalogDb.probeFileLock(tmp.absolutePath))
                        tmp.delete()
                        // report whether the installed engine libraries carry the
                        // flock no-op patch (offsets differ per ABI)
                        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
                        val patches = if (abi.startsWith("x86_64")) listOf(
                            Triple("libfbclient.so", 0x353140L, "31c0c3"),
                            Triple("libEngine13.so", 0xb8d8e0L, "31c0c3"),
                        ) else listOf(
                            Triple("libfbclient.so", 0x34b080L, "00008052c0035fd6"),
                            Triple("libEngine13.so", 0xaf4dc0L, "00008052c0035fd6"),
                        )
                        for ((name, offset, expected) in patches) {
                            val lib = File(activity.applicationInfo.nativeLibraryDir, name)
                            if (lib.exists()) {
                                java.io.RandomAccessFile(lib, "r").use { raf ->
                                    raf.seek(offset)
                                    val b = ByteArray(expected.length / 2)
                                    raf.readFully(b)
                                    val hex = b.joinToString("") { "%02x".format(it) }
                                    probe.append("  |  ").append(name).append(" (").append(abi)
                                        .append("): ")
                                        .append(if (hex == expected) "patched" else "NOT patched ($hex)")
                                }
                            }
                        }
                        status = probe.toString()
                    }) { Text("Diagnose") }

                    OutlinedButton(
                        enabled = dbPath.isNotBlank() && !copying,
                        onClick = { beginCopy(dbPath.trim()) },
                    ) { Text("Copy to app storage") }
                }

                if (copying) {
                    LinearProgressIndicator(
                        progress = { copyPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(status)

                if (handle != null) {
                    Text(
                        "Tap a volume to browse it.",
                        fontSize = 12.sp,
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(volumes) { v ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val h = handle ?: return@clickable
                                try {
                                    val rootId = CatalogDb.rootPathId(h, v.id)
                                    crumbs = listOf(Crumb.Volume(v.id, rootId, v.name))
                                } catch (e: Throwable) {
                                    status = "Cannot open volume: ${e.message}"
                                }
                            }
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(v.name, fontWeight = FontWeight.Bold)
                                v.physicalPath?.let { Text(it, fontSize = 13.sp) }
                                val meta = buildString {
                                    v.catalogDate?.let { append("cataloged $it") }
                                    if (!v.lastUpdateDate.isNullOrBlank()) {
                                        if (isNotEmpty()) append(" · ")
                                        append("updated " + v.lastUpdateDate)
                                    }
                                }
                                if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp)
                                v.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { crumbs = crumbs.dropLast(1) }) { Text("← Up") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${entries.count { it.isFolder }} folders · " +
                                "${entries.count { !it.isFolder }} files",
                        fontSize = 12.sp,
                    )
                }

                Text(
                    crumbs.last().title,
                    fontSize = 22.sp, fontWeight = FontWeight.Bold,
                )
                if (crumbs.size > 1) {
                    Text(
                        crumbs.dropLast(1).joinToString(" / ") { it.title },
                        fontSize = 12.sp,
                    )
                }

                if (entriesLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (entries.isEmpty()) {
                    Text("This folder is empty.")
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries) { e ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (e.isFolder) {
                                    val volumeId = when (val c = crumbs.last()) {
                                        is Crumb.Volume -> c.id
                                        is Crumb.Folder -> c.volumeId
                                    }
                                    crumbs = crumbs + Crumb.Folder(volumeId, e.id, e.name)
                                } else {
                                    fileInfo = e
                                }
                            }
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    (if (e.isFolder) "📁  " else "📄  ") + e.name,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                val meta = buildString {
                                    if (e.isFolder) {
                                        append("folder")
                                    } else {
                                        e.size?.let { append(formatSize(it)) }
                                        e.date?.takeIf { it.isNotBlank() }?.let {
                                            if (isNotEmpty()) append(" · ")
                                            append(it)
                                        }
                                    }
                                }
                                if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp)
                            }
                        }
                    }
                }

                TextButton(onClick = {
                    val removed = closeOpen()
                    status = if (removed) "Catalog closed — private copy deleted."
                    else "Catalog closed."
                }) { Text("Close catalog") }
            }
        }

        val promptPath = rwPromptPath
        if (promptPath != null) {
            val size = File(promptPath).length()
            AlertDialog(
                onDismissRequest = {
                    rwPromptPath = null
                    status = "Open cancelled."
                },
                title = { Text("Catalog is read-write") },
                text = {
                    Text(
                        "This catalog does not carry the read-only flag. Opening it " +
                                "in place lets Firebird update the header and " +
                                "transaction bookkeeping, which changes the file and " +
                                "triggers a full re-sync.\n\n" +
                                "A private copy stays in app storage and leaves the " +
                                "original untouched (${formatSize(size)} to copy). It " +
                                "is deleted when you close the catalog."
                    )
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            rwPromptPath = null
                            openInPlace(promptPath, privateCopy = false)
                        }) { Text("Open in place anyway") }
                        Button(onClick = {
                            rwPromptPath = null
                            beginCopy(promptPath)
                        }) { Text("Copy & open") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        rwPromptPath = null
                        status = "Open cancelled."
                    }) { Text("Cancel") }
                },
            )
        }

        fileInfo?.let { f ->
            AlertDialog(
                onDismissRequest = { fileInfo = null },
                title = { Text(f.name) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        f.size?.let { Text("Size: ${formatSize(it)}") }
                        f.date?.takeIf { it.isNotBlank() }?.let { Text("Modified: $it") }
                        f.ext?.let { Text("Extension: $it") }
                        Text(
                            "Path: " + crumbs.joinToString(" / ") { it.title } +
                                    " / " + f.name,
                            fontSize = 12.sp,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { fileInfo = null }) { Text("Close") }
                },
            )
        }
    }
}
