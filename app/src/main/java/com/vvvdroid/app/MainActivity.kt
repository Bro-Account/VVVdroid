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
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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

    /** A catalog handed over by another app (ACTION_VIEW), consumed by the UI. */
    private var pendingView by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
        // A VIEW intent is only new on a cold start: rotation and theme
        // changes are handled in place, and a restored state means the intent
        // was already consumed (the second catalog from a file manager arrives
        // through onNewIntent instead).
        if (savedInstanceState == null) pendingView = viewData(intent)
        // Debug/automation entry points (used by scripts/emulator-test.ps1):
        //   adb shell am start -n com.vvvdroid.app/.MainActivity \
        //       -e vvv.catalog /sdcard/Download/x.vvv -e vvv.mode auto
        // modes: auto (ask when read-write), copy (always copy), inplace (never ask)
        setContent {
            App(
                activity = this,
                autoPath = intent?.getStringExtra(EXTRA_CATALOG),
                autoMode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_AUTO,
                autoSearch = intent?.getStringExtra(EXTRA_SEARCH),
                viewUri = pendingView,
                onViewConsumed = { pendingView = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewData(intent)?.let { pendingView = it }
    }

    private fun viewData(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_VIEW) intent.data else null

    companion object {
        const val EXTRA_CATALOG = "vvv.catalog"
        const val EXTRA_MODE = "vvv.mode"
        const val EXTRA_SEARCH = "vvv.search"
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

/** Real file behind [uri], if it has one: `file://` paths and `content://`
 *  URIs whose provider exposes a document id on primary storage. Null means
 *  the provider only hands out a stream (sync/cloud apps) or the file is not
 *  reachable (no "All files access"), so the caller copies it instead. */
private fun localPathFor(context: Context, uri: Uri): String? {
    val path = when (uri.scheme) {
        "file" -> uri.path
        "content" -> pathFromUri(context, uri)
        else -> null
    } ?: return null
    return path.takeIf { File(it).isFile }
}

/** Maximum number of search hits shown; the UI says when it is truncated. */
private const val SEARCH_LIMIT = 500

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

/** Display name a content provider reports for [uri] (for status text). */
private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
} catch (_: Exception) {
    null
}

/** Size a content provider reports for [uri], or null when unknown. */
private fun contentSize(context: Context, uri: Uri): Long? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
        }
} catch (_: Exception) {
    null
}

/** Copies a content:// stream to [dest]; [total] is null when the provider
 *  does not report a size (progress is then indeterminate). */
private fun copyUriWithProgress(
    context: Context,
    uri: Uri,
    dest: File,
    total: Long?,
    onProgress: (copied: Long, total: Long?) -> Unit,
) {
    dest.parentFile?.mkdirs()
    var copied = 0L
    var lastReport = 0L
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("the provider returned no stream")
    input.use { source ->
        dest.outputStream().use { output ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = source.read(buf)
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
private fun App(
    activity: Activity,
    autoPath: String?,
    autoMode: String,
    autoSearch: String? = null,
    viewUri: Uri? = null,
    onViewConsumed: () -> Unit = {},
) {
    var hasAllFiles by remember { mutableStateOf(hasStorageAccess(activity)) }
    var dbPath by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf("No catalog open.") }
    var volumes by remember { mutableStateOf<List<CatalogDb.Volume>>(emptyList()) }

    // read-write catalog waiting for the user's decision
    var rwPromptPath by remember { mutableStateOf<String?>(null) }
    var copying by remember { mutableStateOf(false) }
    // 0..100 while copying a file; -1 when the source reports no size
    var copyPercent by remember { mutableStateOf(0) }

    // path of the private copy currently attached (deleted on close)
    var openCopyPath by remember { mutableStateOf<String?>(null) }

    // browse state: one crumb per screen depth
    var crumbs by remember { mutableStateOf<List<Crumb>>(emptyList()) }
    var entries by remember { mutableStateOf<List<CatalogDb.Entry>>(emptyList()) }
    var entriesLoading by remember { mutableStateOf(false) }
    var fileInfo by remember { mutableStateOf<CatalogDb.Entry?>(null) }

    // search state: [searchOpen] shows the search screen, [searchQuery] is the
    // term whose results are on screen
    var searchText by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf<String?>(null) }
    var searchHits by remember { mutableStateOf<List<CatalogDb.SearchHit>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchHitInfo by remember { mutableStateOf<CatalogDb.SearchHit?>(null) }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

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

    fun resetBrowse() {
        crumbs = emptyList()
        entries = emptyList()
        fileInfo = null
        searchOpen = false
        searchQuery = null
        searchHits = emptyList()
        searchHitInfo = null
    }

    fun closeSearch() {
        // Drop focus and the keyboard before the field leaves the screen: a
        // still-focused field plus a closing IME can leave the window panned.
        focusManager.clearFocus()
        keyboard?.hide()
        searchOpen = false
        searchQuery = null
        searchHits = emptyList()
        searchText = ""
    }

    fun runSearch() {
        val term = searchText.trim()
        if (term.isNotEmpty()) searchQuery = term
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
            if (privateCopy) {
                // A copy that cannot be opened is useless; remove it now
                // instead of leaving it until the next start.
                File(path).takeIf { it.exists() }?.delete()
                if (dbPath == path) dbPath = ""
            }
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
                dest.delete()
                activity.runOnUiThread {
                    copying = false
                    status = "Copy failed: ${e.message}"
                }
            }
        }.start()
    }

    /** Copies a content:// stream into app storage and opens it read-only
     *  there. Sync/cloud providers hand out no resolvable file path, and
     *  Firebird needs a real file; the copy is deleted on close. */
    fun beginStreamCopy(uri: Uri) {
        if (copying) {
            status = "A copy is still in progress — please wait."
            return
        }
        val dest = File(File(activity.filesDir, "catalogs"), "private-copy.vvv")
        dest.parentFile?.mkdirs()
        val knownSize = contentSize(activity, uri)
        if (knownSize != null) {
            val need = knownSize + (16L shl 20)
            val free = dest.parentFile?.usableSpace ?: 0L
            if (free < need) {
                status = "Not enough free space in app storage: " +
                        "${formatSize(need)} needed, ${formatSize(free)} available."
                return
            }
        }

        closeOpen()
        if (dest.exists() && !dest.delete()) {
            status = "Could not replace the previous private copy in app storage."
            return
        }

        val name = displayName(activity, uri)
            ?: uri.lastPathSegment
            ?: "catalog"
        copying = true
        copyPercent = if (knownSize != null) 0 else -1
        status = "Copying $name into app storage" +
                (knownSize?.let { " (${formatSize(it)})" } ?: "") +
                "… the copy is deleted when you close it."
        Thread {
            try {
                copyUriWithProgress(activity, uri, dest, knownSize) { done, total ->
                    val pct = if (total != null && total > 0) {
                        ((done * 100) / total).toInt()
                    } else -1
                    activity.runOnUiThread {
                        copyPercent = pct
                        if (pct >= 0) status = "Copying to app storage… $pct%"
                    }
                }
                activity.runOnUiThread {
                    copying = false
                    dbPath = dest.absolutePath
                    openInPlace(dest.absolutePath, privateCopy = true)
                }
            } catch (e: Throwable) {
                dest.delete()
                activity.runOnUiThread {
                    copying = false
                    status = "Could not read the shared file: ${e.message}"
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

    /** Opens a `file://`/`content://` catalog: in place when the URI maps to a
     *  real file, as a private stream copy otherwise. */
    fun openIncoming(uri: Uri) {
        val name = displayName(activity, uri) ?: uri.lastPathSegment ?: "catalog"
        if (copying) {
            status = "A copy is still in progress — please wait."
            return
        }
        closeOpen()
        rwPromptPath = null
        val path = localPathFor(activity, uri)
        if (path != null) {
            dbPath = path
            status = "Opening $name…"
            startOpen(path, MainActivity.MODE_AUTO)
        } else if (uri.scheme == "file") {
            // A file:// path needs "All files access"; a stream copy is not
            // possible because the same permission would be required.
            status = "Cannot read $name — grant \"All files access\" to open " +
                    "catalogs where they are stored."
        } else {
            beginStreamCopy(uri)
        }
    }

    val pickCatalog = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val resolved = localPathFor(activity, uri)
            if (resolved != null) {
                dbPath = resolved
                status = "Catalog selected: $resolved"
            } else {
                // Cloud/sync providers expose a stream only: copy it into app
                // storage (the copy is deleted when the catalog is closed).
                beginStreamCopy(uri)
            }
        }
    }

    // One-shot auto-open for scripts: the activity is recreated on rotation,
    // so remember that the launch intent has already been consumed.
    var autoConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoPath, autoMode, autoSearch) {
        if (!autoPath.isNullOrBlank() && !autoConsumed) {
            autoConsumed = true
            dbPath = autoPath
            startOpen(autoPath, autoMode)
            if (!autoSearch.isNullOrBlank()) {
                searchText = autoSearch
                searchOpen = true
                searchQuery = autoSearch
            }
        }
    }

    // A catalog handed over by another app ("Open with"). The URI is consumed
    // once; the activity does not get recreated for it.
    var viewConsumed by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(viewUri) {
        val uri = viewUri ?: return@LaunchedEffect
        if (viewConsumed == uri.toString()) return@LaunchedEffect
        viewConsumed = uri.toString()
        onViewConsumed()
        openIncoming(uri)
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

    // Search runs off the UI thread; [searchQuery] doubles as the trigger and
    // as the term shown in the results header.
    LaunchedEffect(handle, searchQuery) {
        val h = handle
        val q = searchQuery
        if (h == null || q == null) {
            searchHits = emptyList()
            return@LaunchedEffect
        }
        searchLoading = true
        searchHits = emptyList()
        val found = try {
            withContext(Dispatchers.IO) { CatalogDb.search(h, q, SEARCH_LIMIT) }
        } catch (e: Throwable) {
            activity.runOnUiThread { status = "Search failed: ${e.message}" }
            null
        }
        searchLoading = false
        if (found != null) searchHits = found
    }

    BackHandler(enabled = crumbs.isNotEmpty() || searchOpen) {
        if (crumbs.isNotEmpty()) crumbs = crumbs.dropLast(1)
        else closeSearch()
    }

    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        // Surface provides LocalContentColor (onBackground), so plain Text
        // picks a readable colour in both light and dark themes.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            if (crumbs.isEmpty()) {
                if (handle == null) {
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
                                enabled = hasAllFiles && dbPath.isNotBlank() && !copying,
                                onClick = { startOpen(dbPath.trim(), MainActivity.MODE_AUTO) },
                            ) { Text("Open") }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = dbPath.isNotBlank() && !copying,
                                onClick = { beginCopy(dbPath.trim()) },
                            ) { Text("Copy to app storage") }
                        }

                        if (copying) {
                            if (copyPercent < 0) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(
                                    progress = { copyPercent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        Text(
                            "Tip: any .vvv file can also be opened from a file " +
                                    "manager (\"Open with\").",
                            fontSize = 12.sp,
                        )

                        Text(status)
                    }
                } else if (searchOpen) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { closeSearch() }) { Text("← Back") }
                            Spacer(Modifier.weight(1f))
                            if (!searchLoading && searchQuery != null) {
                                Text("${searchHits.size} matches", fontSize = 12.sp)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                label = { Text("File name contains") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                enabled = searchText.isNotBlank() && !searchLoading,
                                onClick = { runSearch() },
                            ) { Text("Search") }
                        }

                        val term = searchQuery
                        when {
                            term == null -> Text(
                                "Searches file names in all volumes of the catalog.",
                                fontSize = 13.sp,
                            )
                            searchLoading -> LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                            )
                            searchHits.isEmpty() -> Text(
                                "No file name contains \"$term\" in any volume.",
                            )
                            else -> {
                                if (searchHits.size >= SEARCH_LIMIT) {
                                    Text(
                                        "First $SEARCH_LIMIT matches across all volumes.",
                                        fontSize = 12.sp,
                                    )
                                }
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    items(searchHits, key = { it.id }) { hit ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth()
                                                .clickable { searchHitInfo = hit },
                                        ) {
                                            Column(Modifier.padding(12.dp)) {
                                                Text(
                                                    "📄  " + hit.name,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Text(
                                                    buildString {
                                                        append(hit.volumeName.ifBlank { "volume" })
                                                        if (hit.folderPath.isNotBlank()) {
                                                            append(" / " + hit.folderPath)
                                                        }
                                                    },
                                                    fontSize = 12.sp,
                                                )
                                                val meta = buildString {
                                                    hit.size?.let { append(formatSize(it)) }
                                                    hit.date?.takeIf { it.isNotBlank() }?.let {
                                                        if (isNotEmpty()) append(" · ")
                                                        append(it)
                                                    }
                                                }
                                                if (meta.isNotEmpty()) {
                                                    Text(meta, fontSize = 12.sp)
                                                }
                                            }
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
                            Column(Modifier.weight(1f)) {
                                Text("Volumes", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    File(dbPath).name,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { searchOpen = true }) { Text("Search") }
                            TextButton(onClick = {
                                val removed = closeOpen()
                                status = if (removed) "Catalog closed — private copy deleted."
                                else "Catalog closed."
                            }) { Text("Close") }
                        }

                        Text(
                            status,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )

                        if (volumes.isEmpty()) {
                            Text("This catalog has no volumes.")
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

            searchHitInfo?.let { f ->
                AlertDialog(
                    onDismissRequest = { searchHitInfo = null },
                    title = { Text(f.name) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            f.size?.let { Text("Size: ${formatSize(it)}") }
                            f.date?.takeIf { it.isNotBlank() }?.let { Text("Modified: $it") }
                            f.ext?.let { Text("Extension: $it") }
                            Text(
                                "Path: " + listOf(f.volumeName, f.folderPath)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" / ") + " / " + f.name,
                                fontSize = 12.sp,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { searchHitInfo = null }) { Text("Close") }
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
}
