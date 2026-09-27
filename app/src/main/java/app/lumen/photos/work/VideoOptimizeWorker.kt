package app.lumen.photos.work

import android.content.Context
import android.text.format.Formatter
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lumen.photos.LumenApp
import app.lumen.photos.data.settings.VideoOptimizerSettings
import app.lumen.photos.optimize.Outcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class VideoOptimizeJob(val ids: List<Long>, val settings: VideoOptimizerSettings)

/** Compresses a list of videos one after another with the hardware encoder. */
class VideoOptimizeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as LumenApp).container
        return WorkLocks.hold(applicationContext, "video", c.settings.current.keepScreenOnDuringWork) { run() }
    }

    private suspend fun run(): Result {
        val c = (applicationContext as LumenApp).container
        val job = readJob(applicationContext) ?: return Result.failure()
        c.media.reload()
        val byId = c.media.media.value.associateBy { it.id }
        val items = job.ids.mapNotNull { byId[it] }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val title = "Videos werden komprimiert"
        safeForeground(Notifications.progress(applicationContext, Notifications.ID_VIDEO, title, "Starte …", 0, items.size * 100, cancel))

        var done = 0
        var saved = 0L
        var failed = 0
        var skipped = 0
        var lastError: String? = null
        var lastUi = 0L
        for (item in items) {
            if (isStopped) break
            val result = c.videoCompressor.apply(item, job.settings) { percent ->
                val now = System.currentTimeMillis()
                if (now - lastUi > 1000) {
                    lastUi = now
                    val overall = done * 100 + percent
                    setProgressAsync(
                        workDataOf(KEY_DONE to done, KEY_TOTAL to items.size, KEY_SAVED to saved, KEY_PERCENT to percent, KEY_FAILED to failed, KEY_SKIPPED to skipped)
                    )
                    runCatching {
                        setForegroundAsync(
                            Notifications.progress(
                                applicationContext, Notifications.ID_VIDEO, title,
                                "Video ${done + 1} von ${items.size} · $percent % · ${Formatter.formatShortFileSize(applicationContext, saved)} gespart",
                                overall, items.size * 100, cancel
                            )
                        )
                    }
                }
            }
            when (result) {
                is Outcome.Saved -> saved += result.before - result.after
                is Outcome.Skipped -> skipped++
                is Outcome.Failed -> { failed++; lastError = result.error }
            }
            done++
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to items.size, KEY_SAVED to saved, KEY_PERCENT to 0, KEY_FAILED to failed, KEY_SKIPPED to skipped))
        }
        c.media.refresh()
        File(applicationContext.filesDir, JOB_FILE).delete()
        Notifications.done(
            applicationContext, "Videos komprimiert",
            "${Formatter.formatShortFileSize(applicationContext, saved)} freigegeben · ${done - failed - skipped} Videos" +
                (if (skipped > 0) " · $skipped übersprungen" else "") + (if (failed > 0) " · $failed fehlgeschlagen ($lastError)" else "")
        )
        return Result.success(workDataOf(KEY_DONE to done, KEY_TOTAL to items.size, KEY_SAVED to saved, KEY_FAILED to failed, KEY_SKIPPED to skipped))
    }

    companion object {
        const val NAME = "video-optimize"
        const val JOB_FILE = "video_job.json"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_SAVED = "saved"
        const val KEY_PERCENT = "percent"
        const val KEY_FAILED = "failed"
        const val KEY_SKIPPED = "skipped"
        private val json = Json { ignoreUnknownKeys = true }

        fun writeJob(context: Context, job: VideoOptimizeJob) =
            File(context.filesDir, JOB_FILE).writeText(json.encodeToString(VideoOptimizeJob.serializer(), job))

        fun readJob(context: Context): VideoOptimizeJob? = runCatching {
            json.decodeFromString(VideoOptimizeJob.serializer(), File(context.filesDir, JOB_FILE).readText())
        }.getOrNull()
    }
}
