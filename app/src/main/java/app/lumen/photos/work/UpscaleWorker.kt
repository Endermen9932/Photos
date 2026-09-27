package app.lumen.photos.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lumen.photos.LumenApp
import app.lumen.photos.edit.ToolModelCatalog
import app.lumen.photos.edit.Upscaler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Upscales one photo in the background and saves the result as a copy next to it. */
class UpscaleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as LumenApp).container
        return WorkLocks.hold(applicationContext, "upscale", c.settings.current.keepScreenOnDuringWork) { run() }
    }

    private suspend fun run(): Result = withContext(Dispatchers.Default) {
        val c = (applicationContext as LumenApp).container
        val mediaId = inputData.getLong(KEY_MEDIA, -1)
        val factor = inputData.getInt(KEY_FACTOR, 2).coerceIn(1, 4)
        val model = ToolModelCatalog.byId(inputData.getString(KEY_MODEL))
        fun fail(msg: String) = Result.failure(workDataOf(KEY_MEDIA to mediaId, KEY_ERROR to msg))
        if (model == null || !c.models.isInstalled(model)) return@withContext fail("Modell nicht installiert")
        val item = c.media.byId(mediaId) ?: run { c.media.reload(); c.media.byId(mediaId) } ?: return@withContext fail("Foto nicht gefunden")

        val plan = c.tools.plan(item, factor)
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val title = "Hochskalieren · ${model.tier}"
        safeForeground(Notifications.progress(applicationContext, Notifications.ID_UPSCALE, title, "Wird vorbereitet …", 0, 0, cancel))
        setProgress(workDataOf(KEY_MEDIA to mediaId))

        try {
            val source = c.tools.decode(item, if (plan.limited) plan.inputWidth.toLong() * plan.inputHeight else Long.MAX_VALUE)
            val engine = Upscaler(model, c.models, c.settings.current.aiThreads)
            val start = System.currentTimeMillis()
            var lastUi = 0L
            val result = try {
                engine.upscale(source, factor, onTile = { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 700 || done == total) {
                        lastUi = now
                        val eta = (now - start) / done * (total - done) / 1000
                        runBlocking {
                            setProgress(workDataOf(KEY_MEDIA to mediaId, KEY_DONE to done, KEY_TOTAL to total, KEY_ETA to eta))
                            safeForeground(
                                Notifications.progress(
                                    applicationContext, Notifications.ID_UPSCALE, title,
                                    "${done * 100 / total} % · noch ca. ${IndexWorker.formatDuration(eta)}", done, total, cancel
                                )
                            )
                        }
                    }
                }, isCancelled = { isStopped })
            } finally {
                engine.close()
                source.recycle()
            }
            val saved = try {
                c.tools.saveCopy(item, result, "${factor}x")
            } finally {
                result.recycle()
            }
            Notifications.done(applicationContext, "Foto hochskaliert", "${saved.name} · ${plan.outputWidth} × ${plan.outputHeight}")
            Result.success(workDataOf(KEY_MEDIA to mediaId, KEY_NAME to saved.name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            fail("Zu wenig Arbeitsspeicher – versuche einen kleineren Faktor")
        } catch (e: Exception) {
            if (isStopped) fail("Abgebrochen") else fail(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        const val NAME = "upscale"
        const val TAG = "upscale"
        const val KEY_MEDIA = "media"
        const val KEY_MODEL = "model"
        const val KEY_FACTOR = "factor"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ETA = "eta"
        const val KEY_NAME = "name"
        const val KEY_ERROR = "error"
    }
}
