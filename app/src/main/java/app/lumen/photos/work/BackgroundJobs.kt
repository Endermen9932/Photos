package app.lumen.photos.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.BatteryManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import java.util.concurrent.TimeUnit

/** Shared request setup for the long indexing jobs (search index and faces). */
object BackgroundJobs {

    /**
     * Without "only while charging" the job has no constraints at all and is expedited, so it
     * starts right away instead of waiting for JobScheduler. Interrupted runs retry quickly.
     */
    fun request(worker: Class<out ListenableWorker>, chargingOnly: Boolean): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(worker)
            .apply {
                if (chargingOnly) setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                else setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .build()

    /**
     * Only with full photo access is the media list complete. Otherwise (no permission yet, e.g.
     * right after restoring a backup, or only "selected photos") nothing may be deleted from the
     * indexes just because it is not visible.
     */
    fun hasFullMediaAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    fun hasAnyMediaAccess(context: Context): Boolean = hasFullMediaAccess(context) ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

    fun isCharging(context: Context): Boolean =
        context.getSystemService(BatteryManager::class.java)?.isCharging == true

    /** Human readable reason why an enqueued job is not running yet. */
    fun waitingReason(context: Context, state: WorkInfo.State, chargingOnly: Boolean, attempts: Int): String = when {
        state == WorkInfo.State.BLOCKED -> "Wartet auf eine andere Aufgabe …"
        chargingOnly && !isCharging(context) -> "Wartet aufs Laden – „Nur beim Laden indexieren“ ist an"
        attempts > 0 -> "Wurde von Android unterbrochen – wird gleich fortgesetzt …"
        else -> "Wird gestartet …"
    }
}
