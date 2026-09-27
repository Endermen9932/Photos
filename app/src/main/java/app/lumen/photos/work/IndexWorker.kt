package app.lumen.photos.work

import android.content.Context
import android.util.Size
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lumen.photos.LumenApp
import app.lumen.photos.ai.Fp16
import app.lumen.photos.data.db.EmbeddingEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Computes image embeddings for every photo (and optionally video thumbnail) with the active
 * model. Runs as a foreground job, is resumable and only processes new or changed files.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as LumenApp).container
        if (c.settings.current.indexPaused || !BackgroundJobs.hasAnyMediaAccess(applicationContext)) return Result.success()
        return try {
            WorkLocks.hold(applicationContext, "index", c.settings.current.keepScreenOnDuringWork) { run() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never end for good on an unexpected error – WorkManager retries shortly and the
            // job resumes where it stopped.
            android.util.Log.w("IndexWorker", "Indexing interrupted", e)
            Result.retry()
        }
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
        if (all.isEmpty()) return@withContext Result.success()
        val gone = if (BackgroundJobs.hasFullMediaAccess(applicationContext)) known.keys.filter { it !in present } else emptyList()
        if (gone.isNotEmpty()) {
            gone.chunked(500).forEach { dao.delete(model.id, it) }
            c.index.remove(model.id, gone)
        }

        // Photos re-encoded in place by the optimiser keep their vector (marked with -1).
        all.filter { known[it.id] == -1L }.forEach { dao.acceptReplaced(it.id, it.dateModified) }
        val todo = all.filter { val k = known[it.id]; k != it.dateModified && k != -1L }
        if (todo.isEmpty()) return@withContext Result.success()

        c.index.ensureLoaded(model.id)
        var engine = c.ai.engine(model)
        val cancel = WorkActionReceiver.pauseIntent(applicationContext, WorkActionReceiver.ACTION_PAUSE_INDEX)
        val title = "KI-Indexierung · ${model.tier}"
        safeForeground(Notifications.progress(applicationContext, Notifications.ID_INDEX, title, "Wird vorbereitet …", 0, todo.size, cancel, cancelLabel = "Pausieren"))

        val thumbSize = (model.imageSize * 1.5f).toInt().coerceAtLeast(320)
        val batch = ArrayList<EmbeddingEntity>(32)
        var done = 0
        var totalMs = 0L
        var lastUi = 0L
        val resolver = applicationContext.contentResolver
        try {
            for (item in todo) {
                // Model switched or paused meanwhile: stop without touching the index.
                if (isStopped || c.ai.activeModel.value != model || c.settings.current.indexPaused) break
                val t0 = System.currentTimeMillis()
                fun embed(): FloatArray? = runCatching {
                    val bmp = resolver.loadThumbnail(item.uri, Size(thumbSize, thumbSize), null)
                    try {
                        engine.embedImage(bmp)
                    } finally {
                        bmp.recycle()
                    }
                }.getOrNull()
                var vector = embed()
                if (vector == null && engine.isClosed) {
                    // The shared engine was replaced (e.g. new thread count) – never store an
                    // empty vector because of that, just continue with the new engine.
                    if (isStopped || c.ai.activeModel.value != model) break
                    engine = c.ai.engine(model)
                    vector = embed()
                    if (vector == null && engine.isClosed) break
                }
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
                            done, todo.size, cancel, cancelLabel = "Pausieren"
                        )
                    )
                }
            }
        } finally {
            if (batch.isNotEmpty()) withContext(kotlinx.coroutines.NonCancellable) { dao.upsert(batch.toList()) }
            if (!engine.isClosed) engine.releaseVision()
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
