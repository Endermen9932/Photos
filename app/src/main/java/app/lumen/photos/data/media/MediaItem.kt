package app.lumen.photos.data.media

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    /** Capture time in epoch millis (falls back to modification time). */
    val timestamp: Long,
    /** Last modification in epoch seconds, used to detect changed files. */
    val dateModified: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val bucketId: Long,
    val bucketName: String,
    val relativePath: String,
    val durationMs: Long,
    val isFavorite: Boolean,
    val isTrashed: Boolean = false,
    /** Epoch seconds at which a trashed item is deleted for good. */
    val expiresAt: Long = 0,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isImage: Boolean get() = !isVideo
    val isScreenshot: Boolean
        get() = relativePath.contains("Screenshot", ignoreCase = true) || name.startsWith("Screenshot", ignoreCase = true)

    /** Width/height after applying EXIF rotation. */
    val displayWidth: Int get() = if (orientation == 90 || orientation == 270) height else width
    val displayHeight: Int get() = if (orientation == 90 || orientation == 270) width else height
    val aspectRatio: Float
        get() = if (displayWidth > 0 && displayHeight > 0) displayWidth.toFloat() / displayHeight else 1f
    val megapixels: Float get() = width.toLong() * height / 1_000_000f
}

@Immutable
data class Album(
    val id: Long,
    val name: String,
    val relativePath: String,
    val cover: MediaItem,
    val count: Int,
    val totalSize: Long,
    val latest: Long,
)
