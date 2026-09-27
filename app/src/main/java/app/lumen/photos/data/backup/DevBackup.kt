package app.lumen.photos.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.room.InvalidationTracker
import app.lumen.photos.AppContainer
import app.lumen.photos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Contents of `backup.json` in the backup folder. */
@Serializable
data class DevBackupInfo(
    val createdAt: Long,
    val appVersion: String,
    val installId: String,
    val models: List<String>,
    val modelBytes: Long,
    val databaseBytes: Long,
)

data class DevBackupState(
    val running: Boolean = false,
    /** What is happening right now, e.g. "Modelle sichern …". */
    val step: String? = null,
    val progress: Float? = null,
    val info: DevBackupInfo? = null,
    /** The backup in the folder was made by another installation (e.g. before reinstalling). */
    val foreign: Boolean = false,
    val error: String? = null,
)

/**
 * Developer mode: keeps a complete copy of everything the app produces – AI and face models,
 * search index, faces & names, optimiser history and all settings – in `Documents/Photos`.
 * That folder survives uninstalling, so after installing a new version everything is restored
 * with one tap instead of downloading, indexing and configuring again.
 *
 * Needs "Zugriff auf alle Dateien": without it an app cannot read files a previous installation
 * created in shared storage.
 */
class DevBackup(private val context: Context, private val c: AppContainer) {

    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(DevBackupState())
    val state: StateFlow<DevBackupState> = _state.asStateFlow()

    private val modelsDir get() = File(context.filesDir, "models")
    private val settingsFile get() = File(context.filesDir, "datastore/$SETTINGS_FILE")

    /** Stable id of this installation; a restore adopts the id of the backup. */
    private val installId: String
        get() = File(context.filesDir, INSTALL_ID_FILE).let { f ->
            f.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: UUID.randomUUID().toString().also { f.writeText(it) }
        }

    fun start(justRestored: Boolean) {
        refreshInfo()
        if (justRestored) {
            c.scope.launch {
                // The SAF permission of the photo backup target does not survive a reinstall.
                c.settings.updateBackup { it.copy(treeUri = null, folderName = null, autoBackup = false) }
            }
        }
        c.models.onDeleted = { model ->
            if (enabled()) runCatching { File(root, "models/${model.id}").deleteRecursively() }
            requestBackup()
        }
        // Everything that changes app files requests a backup.
        c.db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(TABLES) {
            override fun onInvalidated(tables: Set<String>) = requestBackup()
        })
        c.settings.settings.drop(1).onEach { requestBackup() }.launchIn(c.scope)
        c.models.installed.drop(1).onEach { requestBackup() }.launchIn(c.scope)
        c.settings.settings.map { it.developerMode }.distinctUntilChanged().onEach { on ->
            if (on) { refreshInfo(); requestBackup() }
        }.launchIn(c.scope)

        c.scope.launch {
            while (true) {
                requests.receive()
                // Let bursts settle (indexing writes every few seconds), but back up at least
                // every 10 minutes while something keeps changing.
                val first = System.currentTimeMillis()
                while (System.currentTimeMillis() - first < MAX_DELAY_MS) {
                    withTimeoutOrNull(SETTLE_MS) { requests.receive() } ?: break
                }
                if (enabled() && !_state.value.foreign) runCatching { backupNow() }
            }
        }
    }

    fun requestBackup() {
        if (enabled()) requests.trySend(Unit)
    }

    private fun enabled() = c.settings.current.developerMode && hasAccess()

    fun refreshInfo() {
        val info = if (hasAccess()) readInfo() else null
        _state.update { it.copy(info = info, foreign = info != null && info.installId != installId) }
    }

    private fun readInfo(): DevBackupInfo? = runCatching {
        json.decodeFromString(DevBackupInfo.serializer(), File(root, MANIFEST).readText())
    }.getOrNull()

    // ------------------------------------------------------------------ backup

    /** Copies everything to [root]. Models are copied incrementally, the rest every time. */
    suspend fun backupNow(): Result<DevBackupInfo> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                check(hasAccess()) { "Kein Zugriff auf alle Dateien" }
                _state.update { it.copy(running = true, step = "Modelle sichern …", progress = null, error = null) }
                val target = root.apply { mkdirs() }
                check(target.isDirectory) { "Ordner ${target.path} konnte nicht angelegt werden" }

                // Models (only complete files – running downloads end with .part).
                val models = modelsDir.listFiles()?.filter { it.isDirectory }.orEmpty()
                val files = models.flatMap { dir -> dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".tmp") }.orEmpty() }
                val modelBytes = files.sumOf { it.length() }
                var copied = 0L
                for (src in files) {
                    val dst = File(target, "models/${src.parentFile!!.name}/${src.name}")
                    if (!dst.exists() || dst.length() != src.length()) copyAtomically(src, dst) { n ->
                        _state.update { it.copy(progress = ((copied + n).toFloat() / modelBytes.coerceAtLeast(1)).coerceIn(0f, 1f)) }
                    }
                    copied += src.length()
                }

                // Database: VACUUM INTO gives a consistent snapshot while indexing keeps writing.
                _state.update { it.copy(step = "Index & Gesichter sichern …", progress = null) }
                val snapshot = File(context.cacheDir, "backup-$DB_NAME").apply { delete() }
                c.db.openHelper.writableDatabase.execSQL("VACUUM INTO '${snapshot.absolutePath.replace("'", "''")}'")
                val databaseBytes = snapshot.length()
                copyAtomically(snapshot, File(target, DB_NAME))
                snapshot.delete()

                _state.update { it.copy(step = "Einstellungen sichern …") }
                if (settingsFile.exists()) copyAtomically(settingsFile, File(target, SETTINGS_FILE))

                val info = DevBackupInfo(
                    createdAt = System.currentTimeMillis(),
                    appVersion = BuildConfig.VERSION_NAME,
                    installId = installId,
                    models = models.map { it.name }.sorted(),
                    modelBytes = modelBytes,
                    databaseBytes = databaseBytes,
                )
                File(target, MANIFEST).writeText(json.encodeToString(DevBackupInfo.serializer(), info))
                info
            }.also { result ->
                _state.update {
                    it.copy(
                        running = false, step = null, progress = null,
                        info = result.getOrNull() ?: it.info,
                        foreign = if (result.isSuccess) false else it.foreign,
                        error = result.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ restore

    /**
     * Copies the models back right away and stages database and settings; they are moved into
     * place by [applyPendingRestore] on the next start. Afterwards call [restartApp].
     */
    suspend fun restore(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                check(hasAccess()) { "Kein Zugriff auf alle Dateien" }
                val info = readInfo() ?: error("Kein Backup in ${root.path} gefunden")
                // Nothing may write into the database that is about to be replaced.
                c.ai.cancelIndexing()
                c.faces.cancel()
                _state.update { it.copy(running = true, step = "Modelle wiederherstellen …", progress = 0f, error = null) }

                val files = File(root, "models").listFiles()?.filter { it.isDirectory }.orEmpty()
                    .flatMap { dir -> dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty() }
                val total = files.sumOf { it.length() }.coerceAtLeast(1)
                var copied = 0L
                for (src in files) {
                    val dst = File(modelsDir, "${src.parentFile!!.name}/${src.name}")
                    if (!dst.exists() || dst.length() != src.length()) copyAtomically(src, dst) { n ->
                        _state.update { it.copy(progress = ((copied + n).toFloat() / total).coerceIn(0f, 1f)) }
                    }
                    copied += src.length()
                }
                c.models.refresh()

                _state.update { it.copy(step = "Index, Gesichter & Einstellungen vorbereiten …", progress = null) }
                val pending = pendingDir(context).apply { deleteRecursively(); mkdirs() }
                File(root, DB_NAME).takeIf { it.exists() }?.copyTo(File(pending, DB_NAME), overwrite = true)
                File(root, SETTINGS_FILE).takeIf { it.exists() }?.copyTo(File(pending, SETTINGS_FILE), overwrite = true)
                File(pending, READY_MARKER).writeText(info.installId)
                _state.update { it.copy(step = "Neustart …") }
            }.onFailure { e ->
                pendingDir(context).deleteRecursively()
                _state.update { it.copy(running = false, step = null, progress = null, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private suspend fun copyAtomically(src: File, dst: File, onProgress: (Long) -> Unit = {}) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".tmp")
        src.inputStream().use { input ->
            tmp.outputStream().use { out ->
                val buffer = ByteArray(1 shl 20)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    if (done - lastReport > 8L * 1024 * 1024) {
                        lastReport = done
                        onProgress(done)
                    }
                }
                out.fd.sync()
            }
        }
        dst.delete()
        check(tmp.renameTo(dst)) { "Konnte ${dst.name} nicht speichern" }
    }

    companion object {
        /** `Documents/Photos` on the internal shared storage. */
        val root: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Photos")
        const val FOLDER_LABEL = "Documents/Photos"

        private const val DB_NAME = "lumen.db"
        private const val SETTINGS_FILE = "settings.preferences_pb"
        private const val MANIFEST = "backup.json"
        private const val INSTALL_ID_FILE = "install_id"
        private const val READY_MARKER = "ready"
        private const val SETTLE_MS = 45_000L
        private const val MAX_DELAY_MS = 10 * 60_000L
        private val TABLES = arrayOf("embeddings", "optimized", "faces", "face_scans", "persons", "face_rejections")
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun hasAccess(): Boolean = Environment.isExternalStorageManager()

        fun accessIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

        /** True if the folder contains a backup (only readable with [hasAccess]). */
        fun backupExists(): Boolean = hasAccess() && File(root, MANIFEST).exists()

        private fun pendingDir(context: Context) = File(context.filesDir, "restore-pending")

        /**
         * Moves a staged restore into place. Runs in `Application.onCreate` before the database
         * and the settings store are opened. Returns true if a restore was applied.
         */
        fun applyPendingRestore(context: Context): Boolean {
            val dir = pendingDir(context)
            val marker = File(dir, READY_MARKER)
            if (!marker.exists()) {
                if (dir.exists()) dir.deleteRecursively()
                return false
            }
            val applied = runCatching {
                File(dir, DB_NAME).takeIf { it.exists() }?.let { db ->
                    val target = context.getDatabasePath(DB_NAME)
                    target.parentFile?.mkdirs()
                    listOf("", "-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
                    if (!db.renameTo(target)) db.copyTo(target, overwrite = true)
                }
                File(dir, SETTINGS_FILE).takeIf { it.exists() }?.let { f ->
                    val target = File(context.filesDir, "datastore/$SETTINGS_FILE")
                    target.parentFile?.mkdirs()
                    target.delete()
                    if (!f.renameTo(target)) f.copyTo(target, overwrite = true)
                }
                // Adopt the identity of the backup so the next automatic backup may update it.
                File(context.filesDir, INSTALL_ID_FILE).writeText(marker.readText())
            }.isSuccess
            dir.deleteRecursively()
            return applied
        }

        /** Restarts the process so database and settings are opened from the restored files. */
        fun restartApp(context: Context) {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            context.startActivity(Intent.makeRestartActivityTask(launch.component))
            Runtime.getRuntime().exit(0)
        }
    }
}
