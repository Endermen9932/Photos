package app.lumen.photos.work

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lumen.photos.LumenApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Incremental backup of the whole gallery into a user-chosen folder (Storage Access Framework:
 * USB stick, SD card, Nextcloud/NAS document providers …) and restore from it. The album
 * structure (e.g. DCIM/Camera) is mirrored; unchanged files are skipped.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private class Dir(val file: DocumentFile, val children: HashMap<String, DocumentFile>)

    override suspend fun doWork(): Result {
        val c = (applicationContext as LumenApp).container
        return WorkLocks.hold(applicationContext, "backup", c.settings.current.keepScreenOnDuringWork) {
            withContext(Dispatchers.IO) {
                if (inputData.getString(KEY_MODE) == MODE_RESTORE) restore() else backup()
            }
        }
    }

    private fun root(): DocumentFile? {
        val c = (applicationContext as LumenApp).container
        val uri = c.settings.current.backup.treeUri ?: return null
        val tree = DocumentFile.fromTreeUri(applicationContext, Uri.parse(uri)) ?: return null
        if (!tree.canWrite()) return null
        return tree.findFile(ROOT_NAME)?.takeIf { it.isDirectory } ?: tree.createDirectory(ROOT_NAME)
    }

    private suspend fun backup(): Result {
        val c = (applicationContext as LumenApp).container
        val settings = c.settings.current.backup
        val root = root() ?: return Result.failure(workDataOf(KEY_ERROR to "Backup-Ordner nicht erreichbar"))
        c.media.reload()
        val items = c.media.media.value.filter { settings.includeVideos || it.isImage }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val title = "Backup läuft"
        setForeground(Notifications.progress(applicationContext, Notifications.ID_BACKUP, title, "Vergleiche Dateien …", 0, items.size, cancel, dataSync = true))

        fun listing(d: DocumentFile) = HashMap(d.listFiles().associateBy { it.name ?: "" })
        val dirs = HashMap<String, Dir>()
        dirs[""] = Dir(root, listing(root))
        fun dirFor(path: String): Dir? {
            dirs[path]?.let { return it }
            val parent = dirFor(path.substringBeforeLast('/', "")) ?: return null
            val name = path.substringAfterLast('/')
            val d = parent.children[name]?.takeIf { it.isDirectory }
                ?: parent.file.createDirectory(name)?.also { parent.children[name] = it }
                ?: return null
            return Dir(d, listing(d)).also { dirs[path] = it }
        }

        var done = 0
        var copied = 0
        var bytes = 0L
        var failed = 0
        var lastUi = 0L
        for (item in items) {
            if (isStopped) break
            runCatching {
                val dir = dirFor(item.relativePath.trim('/')) ?: error("Ordner")
                val existing = dir.children[item.name]
                if (existing == null || existing.length() != item.size) {
                    val target = existing ?: dir.file.createFile("application/octet-stream", item.name)?.also { dir.children[item.name] = it }
                        ?: error("Datei")
                    applicationContext.contentResolver.openInputStream(item.uri)!!.use { input ->
                        applicationContext.contentResolver.openOutputStream(target.uri, "wt")!!.use { input.copyTo(it, 1 shl 16) }
                    }
                    copied++
                    bytes += item.size
                }
            }.onFailure { failed++ }
            done++
            val now = System.currentTimeMillis()
            if (now - lastUi > 700 || done == items.size) {
                lastUi = now
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to items.size, KEY_COPIED to copied, KEY_BYTES to bytes, KEY_MODE to MODE_BACKUP))
                setForeground(
                    Notifications.progress(
                        applicationContext, Notifications.ID_BACKUP, title,
                        "$done von ${items.size} · $copied neu (${Formatter.formatShortFileSize(applicationContext, bytes)})",
                        done, items.size, cancel, dataSync = true
                    )
                )
            }
        }
        if (!isStopped) {
            c.settings.updateBackup { it.copy(lastBackupAt = System.currentTimeMillis(), lastBackupCopied = copied) }
            Notifications.done(
                applicationContext,
                "Backup abgeschlossen",
                "$copied neue Dateien (${Formatter.formatShortFileSize(applicationContext, bytes)}) gesichert, ${items.size - copied - failed} waren schon aktuell" +
                    if (failed > 0) ", $failed Fehler" else ""
            )
        }
        return Result.success(workDataOf(KEY_DONE to done, KEY_TOTAL to items.size, KEY_COPIED to copied, KEY_BYTES to bytes, KEY_FAILED to failed, KEY_MODE to MODE_BACKUP))
    }

    /** Copies files from the backup back into the gallery that are missing there. */
    private suspend fun restore(): Result {
        val c = (applicationContext as LumenApp).container
        val root = root() ?: return Result.failure(workDataOf(KEY_ERROR to "Backup-Ordner nicht erreichbar"))
        c.media.reload()
        val present = HashSet<String>()
        val trashed = c.media.trashed()
        (c.media.media.value + trashed).forEach { present += key(it.relativePath.trim('/'), it.name, it.size) }

        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val title = "Wiederherstellung läuft"
        setForeground(Notifications.progress(applicationContext, Notifications.ID_BACKUP, title, "Durchsuche Backup …", 0, 0, cancel, dataSync = true))

        val files = ArrayList<Pair<String, DocumentFile>>()
        fun walk(dir: DocumentFile, path: String) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (f.isDirectory) walk(f, if (path.isEmpty()) name else "$path/$name")
                else if (mimeOf(name) != null) files += path to f
            }
        }
        walk(root, "")

        val missing = files.filter { (path, f) -> key(path, f.name ?: "", f.length()) !in present }
        var done = 0
        var restored = 0
        var failed = 0
        var lastUi = 0L
        for ((path, file) in missing) {
            if (isStopped) break
            runCatching {
                val name = file.name!!
                val mime = mimeOf(name)!!
                val video = mime.startsWith("video/")
                val relative = when {
                    path.startsWith("DCIM", true) || path.startsWith("Pictures", true) -> path
                    video && path.startsWith("Movies", true) -> path
                    else -> "Pictures/Wiederhergestellt/${path.ifBlank { "Backup" }}"
                } + "/"
                val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    if (file.lastModified() > 0) put(MediaStore.MediaColumns.DATE_TAKEN, file.lastModified())
                }
                val uri = applicationContext.contentResolver.insert(collection, values)!!
                try {
                    applicationContext.contentResolver.openInputStream(file.uri)!!.use { input ->
                        applicationContext.contentResolver.openOutputStream(uri, "w")!!.use { input.copyTo(it, 1 shl 16) }
                    }
                    applicationContext.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    applicationContext.contentResolver.delete(uri, null, null)
                    throw e
                }
                restored++
            }.onFailure { failed++ }
            done++
            val now = System.currentTimeMillis()
            if (now - lastUi > 700 || done == missing.size) {
                lastUi = now
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to missing.size, KEY_COPIED to restored, KEY_MODE to MODE_RESTORE))
                setForeground(
                    Notifications.progress(applicationContext, Notifications.ID_BACKUP, title, "$done von ${missing.size}", done, missing.size, cancel, dataSync = true)
                )
            }
        }
        c.media.refresh()
        Notifications.done(
            applicationContext, "Wiederherstellung abgeschlossen",
            if (missing.isEmpty()) "Alle Dateien aus dem Backup sind bereits in der Galerie."
            else "$restored Dateien wiederhergestellt" + if (failed > 0) ", $failed Fehler" else ""
        )
        return Result.success(workDataOf(KEY_DONE to done, KEY_TOTAL to missing.size, KEY_COPIED to restored, KEY_FAILED to failed, KEY_MODE to MODE_RESTORE))
    }

    private fun key(path: String, name: String, size: Long) = "${path.lowercase(Locale.ROOT)}/$name#$size"

    private fun mimeOf(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "heic", "heif" -> "image/heif"
            "avif" -> "image/avif"
            else -> null
        }
        return mime?.takeIf { it.startsWith("image/") || it.startsWith("video/") }
    }

    companion object {
        const val NAME = "backup"
        const val PERIODIC = "backup-auto"
        const val ROOT_NAME = "Lumen-Backup"
        const val KEY_MODE = "mode"
        const val MODE_BACKUP = "backup"
        const val MODE_RESTORE = "restore"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_COPIED = "copied"
        const val KEY_BYTES = "bytes"
        const val KEY_FAILED = "failed"
        const val KEY_ERROR = "error"

        fun start(context: Context, mode: String) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(workDataOf(KEY_MODE to mode))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** Nightly incremental backup while charging (e.g. to an always attached drive or NAS). */
        fun schedulePeriodic(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_MODE to MODE_BACKUP))
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
