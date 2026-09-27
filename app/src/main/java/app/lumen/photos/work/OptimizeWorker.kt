package app.lumen.photos.work

import android.content.Context
import android.text.format.Formatter
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lumen.photos.LumenApp
import app.lumen.photos.data.settings.OptimizerSettings
import app.lumen.photos.optimize.Outcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class OptimizeJob(val ids: List<Long>, val settings: OptimizerSettings)

/** Optimises a list of photos in the background with a progress notification. */
class OptimizeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as LumenApp).container
        return WorkLocks.hold(applicationContext, "optimize", c.settings.current.keepScreenOnDuringWork) { run() }
    }

    private suspend fun run(): Result {
        val c = (applicationContext as LumenApp).container
        val job = readJob(applicationContext) ?: return Result.failure()
        c.media.reload()
        val byId = c.media.media.value.associateBy { it.id }
        val items = job.ids.mapNotNull { byId[it] }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val title = "Speicher wird optimiert"
        setForeground(Notifications.progress(applicationContext, Notifications.ID_OPTIMIZE, title, "Starte …", 0, items.size, cancel))

        var done = 0
        var saved = 0L
        var failed = 0
        var skipped = 0
        var lastUi = 0L
        var lastError: String? = null
        for (item in items) {
            if (isStopped) break
            when (val r = c.optimizer.apply(item, job.settings)) {
                is Outcome.Saved -> saved += r.before - r.after
                is Outcome.Skipped -> skipped++
                is Outcome.Failed -> { failed++; lastError = r.error }
            }
            done++
            val now = System.currentTimeMillis()
            if (now - lastUi > 600 || done == items.size) {
                lastUi = now
                setProgress(
                    workDataOf(KEY_DONE to done, KEY_TOTAL to items.size, KEY_SAVED to saved, KEY_FAILED to failed, KEY_SKIPPED to skipped)
                )
                setForeground(
                    Notifications.progress(
                        applicationContext, Notifications.ID_OPTIMIZE, title,
                        "$done von ${items.size} · ${Formatter.formatShortFileSize(applicationContext, saved)} gespart",
                        done, items.size, cancel
                    )
                )
            }
        }
        c.media.refresh()
        File(applicationContext.filesDir, JOB_FILE).delete()
        val summary = buildString {
            append("${Formatter.formatShortFileSize(applicationContext, saved)} freigegeben · ${done - failed - skipped} Fotos optimiert")
            if (skipped > 0) append(" · $skipped übersprungen")
            if (failed > 0) append(" · $failed fehlgeschlagen ($lastError)")
        }
        Notifications.done(applicationContext, "Speicheroptimierung abgeschlossen", summary)
        return Result.success(
            workDataOf(KEY_DONE to done, KEY_TOTAL to items.size, KEY_SAVED to saved, KEY_FAILED to failed, KEY_SKIPPED to skipped)
        )
    }

    companion object {
        const val NAME = "optimize"
        const val JOB_FILE = "optimize_job.json"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_SAVED = "saved"
        const val KEY_FAILED = "failed"
        const val KEY_SKIPPED = "skipped"

        private val json = Json { ignoreUnknownKeys = true }

        fun writeJob(context: Context, job: OptimizeJob) {
            File(context.filesDir, JOB_FILE).writeText(json.encodeToString(OptimizeJob.serializer(), job))
        }

        fun readJob(context: Context): OptimizeJob? = runCatching {
            json.decodeFromString(OptimizeJob.serializer(), File(context.filesDir, JOB_FILE).readText())
        }.getOrNull()
    }
}
