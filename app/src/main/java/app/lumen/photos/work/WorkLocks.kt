package app.lumen.photos.work

import android.content.Context
import android.os.PowerManager

/**
 * Wake locks held while long jobs (indexing, optimising) run: the CPU never sleeps, and – if the
 * user wants it – the display is kept on but dimmed so the job keeps full speed.
 */
object WorkLocks {
    private const val MAX_HOLD_MS = 12 * 60 * 60 * 1000L

    @Suppress("DEPRECATION")
    suspend fun <T> hold(context: Context, tag: String, keepScreenOn: Boolean, block: suspend () -> T): T {
        val pm = context.getSystemService(PowerManager::class.java)
        val cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lumen:$tag").apply {
            setReferenceCounted(false)
            acquire(MAX_HOLD_MS)
        }
        // SCREEN_DIM keeps the display on at minimum brightness – it dims, but never turns off.
        val screen = if (keepScreenOn) {
            runCatching {
                pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "Lumen:$tag-screen").apply {
                    setReferenceCounted(false)
                    acquire(MAX_HOLD_MS)
                }
            }.getOrNull()
        } else null
        return try {
            block()
        } finally {
            runCatching { screen?.release() }
            runCatching { cpu.release() }
        }
    }
}
