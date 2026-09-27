package app.lumen.photos.work

import android.content.Context
import android.util.Size
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lumen.photos.LumenApp
import app.lumen.photos.ai.Fp16
import app.lumen.photos.data.db.EmbeddingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Computes image embeddings for every photo (and optionally video thumbnail) with the active
 * model. Runs as a foreground job, is resumable and only processes new or changed files.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as LumenApp).container
        return WorkLocks.hold(applicationContext, "index", c.settings.current.keepScreenOnDuringWork) { run() }
    }

    private suspend fun run(): Result = withContext(Dispatchers.Default) {
        val app = applicationContext as LumenApp
        val c = app.container
        val settings = c.settings.current
        val model = c.ai.activeModel.value ?: return@withContext Result.success()
        if (!c.models.isInstalled(model)) return@withContext Result.failure()

        c.media.reload()
        val all = c.media.media.value.filter { settings.indexVideos || it.isImage }
        val dao = c.db.embeddings()
        val known = dao.keys(model.id).associate { it.mediaId to it.dateModified }

        // Forget files that no longer exist.
        val present = all.mapTo(HashSet()) { it.id }
        val gone = known.keys.filter { it !in present }
        if (gone.isNotEmpty()) {
            gone.chunked(500).forEach { dao.delete(model.id, it) }
            c.index.remove(model.id, gone)
        }

        val todo = all.filter { known[it.id] != it.dateModified }
        if (todo.isEmpty()) return@withContext Result.success()

        c.index.ensureLoaded(model.id)
        val engine = c.ai.engine(model)
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val title = "KI-Indexierung · ${model.tier}"
        safeForeground(Notifications.progress(applicationContext, Notifications.ID_INDEX, title, "Wird vorbereitet …", 0, todo.size, cancel))

        val thumbSize = (model.imageSize * 1.5f).toInt().coerceAtLeast(320)
        val batch = ArrayList<EmbeddingEntity>(32)
        var done = 0
        var totalMs = 0L
        var lastUi = 0L
        val resolver = applicationContext.contentResolver
        try {
            for (item in todo) {
                if (isStopped) break
                val t0 = System.currentTimeMillis()
                val vector: FloatArray? = runCatching {
                    val bmp = resolver.loadThumbnail(item.uri, Size(thumbSize, thumbSize), null)
                    try {
                        engine.embedImage(bmp)
                    } finally {
                        bmp.recycle()
                    }
                }.getOrNull()
                totalMs += System.currentTimeMillis() - t0
                batch += EmbeddingEntity(item.id, model.id, item.dateModified, vector?.let { Fp16.encode(it) } ?: ByteArray(0))
                if (vector != null) c.index.put(model.id, item.id, vector)
                done++

                if (batch.size >= 32) {
                    dao.upsert(batch.toList())
                    batch.clear()
                }
                val now = System.currentTimeMillis()
                if (now - lastUi > 800 || done == todo.size) {
                    lastUi = now
                    val ms = (totalMs / done).toInt()
                    setProgress(
                        workDataOf(KEY_DONE to done, KEY_TOTAL to todo.size, KEY_MS to ms, KEY_MODEL to model.id)
                    )
                    val remaining = (todo.size - done).toLong() * ms / 1000
                    safeForeground(
                        Notifications.progress(
                            applicationContext, Notifications.ID_INDEX, title,
                            "$done von ${todo.size} · noch ca. ${formatDuration(remaining)}",
                            done, todo.size, cancel
                        )
                    )
                }
            }
        } finally {
            if (batch.isNotEmpty()) withContext(kotlinx.coroutines.NonCancellable) { dao.upsert(batch.toList()) }
            engine.releaseVision()
        }
        if (done == todo.size && todo.size > 20) {
            Notifications.done(
                applicationContext,
                "KI-Suche bereit",
                "$done Fotos wurden mit ${model.name} indexiert. Du kannst jetzt nach Inhalten suchen."
            )
        }
        Result.success()
    }

    companion object {
        const val NAME = "ai-index"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_MS = "ms"
        const val KEY_MODEL = "model"

        fun formatDuration(seconds: Long): String = when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min"
            else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
        }
    }
}
