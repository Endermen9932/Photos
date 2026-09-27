package app.lumen.photos.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.lumen.photos.container
import kotlinx.coroutines.launch

/** "Pausieren" button in the progress notifications of the indexing jobs. */
class WorkActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        val pending = goAsync()
        c.scope.launch {
            try {
                when (intent.action) {
                    ACTION_PAUSE_INDEX -> c.ai.pauseIndexing()
                    ACTION_PAUSE_FACES -> c.faces.pause()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE_INDEX = "app.lumen.photos.PAUSE_INDEX"
        const val ACTION_PAUSE_FACES = "app.lumen.photos.PAUSE_FACES"

        fun pauseIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, WorkActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
