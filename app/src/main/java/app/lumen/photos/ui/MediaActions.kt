package app.lumen.photos.ui

import android.app.WallpaperManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.lumen.photos.container
import app.lumen.photos.data.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** User actions on media that need system consent dialogs or other apps. */
class MediaActions(private val context: Context, private val launcher: IntentSenderLauncher) {
    private val repo = context.container.media

    private suspend fun runRequest(block: () -> android.content.IntentSender): Boolean {
        val ok = runCatching { launcher.launch(block()) }.getOrDefault(false)
        if (ok) repo.reload()
        return ok
    }

    suspend fun trash(items: List<MediaItem>): Boolean =
        items.isNotEmpty() && runRequest { repo.trashRequest(items.map { it.uri }, true) }

    suspend fun restore(items: List<MediaItem>): Boolean =
        items.isNotEmpty() && runRequest { repo.trashRequest(items.map { it.uri }, false) }

    suspend fun deleteForever(items: List<MediaItem>): Boolean =
        items.isNotEmpty() && runRequest { repo.deleteRequest(items.map { it.uri }) }

    suspend fun setFavorite(items: List<MediaItem>, favorite: Boolean): Boolean =
        items.isNotEmpty() && runRequest { repo.favoriteRequest(items.map { it.uri }, favorite) }

    /** Asks for write access (one dialog, or none with "media management" permission). */
    suspend fun requestWrite(items: List<MediaItem>): Boolean {
        if (items.isEmpty()) return true
        for (chunk in items.chunked(1500)) {
            val ok = runCatching { launcher.launch(repo.writeRequest(chunk.map { it.uri })) }.getOrDefault(false)
            if (!ok) return false
        }
        return true
    }

    suspend fun move(items: List<MediaItem>, relativePath: String): Int {
        if (!requestWrite(items)) return 0
        return repo.move(items, relativePath)
    }

    fun share(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val intent = if (items.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = items[0].mimeType
                putExtra(Intent.EXTRA_STREAM, items[0].uri)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = if (items.all { it.isImage }) "image/*" else if (items.all { it.isVideo }) "video/*" else "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
            }
        }
        intent.clipData = ClipData.newRawUri(null, items[0].uri).apply { items.drop(1).forEach { addItem(ClipData.Item(it.uri)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun useAs(item: MediaItem) {
        val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
            setDataAndType(item.uri, item.mimeType)
            putExtra("mimeType", item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Verwenden als").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun setWallpaper(item: MediaItem) {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(item.uri)?.use {
                    WallpaperManager.getInstance(context).setStream(it)
                }
            }.isSuccess
        }
        Toast.makeText(context, if (ok) "Hintergrund gesetzt" else "Hintergrund konnte nicht gesetzt werden", Toast.LENGTH_SHORT).show()
    }

    fun openWith(item: MediaItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Öffnen mit").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun rememberMediaActions(): MediaActions {
    val context = LocalContext.current
    val launcher = LocalIntentSenderLauncher.current
    return remember(context, launcher) { MediaActions(context, launcher) }
}
