package app.lumen.photos

import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.data.media.MediaRepository
import app.lumen.photos.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * Named media lists that can be opened in the viewer. Built-in sources are live views of the
 * MediaStore; ad-hoc lists (search results, memories, duplicates …) are registered at runtime.
 */
class MediaListRegistry {
    private val custom = ConcurrentHashMap<String, MutableStateFlow<List<Long>>>()

    fun register(key: String, items: List<MediaItem>): String {
        custom.getOrPut(key) { MutableStateFlow(emptyList()) }.value = items.map { it.id }
        return key
    }

    fun source(key: String, media: MediaRepository, settings: SettingsRepository): Flow<List<MediaItem>> = when {
        key == SOURCE_TIMELINE -> combine(media.media, settings.settings) { list, s ->
            if (s.showVideosInTimeline) list else list.filter { it.isImage }
        }
        key == SOURCE_FAVORITES -> media.media.map { list -> list.filter { it.isFavorite } }
        key == SOURCE_VIDEOS -> media.media.map { list -> list.filter { it.isVideo } }
        key == SOURCE_SCREENSHOTS -> media.media.map { list -> list.filter { it.isScreenshot } }
        key == SOURCE_LARGE -> media.media.map { list -> list.sortedByDescending { it.size }.take(300) }
        key == SOURCE_TRASH -> combine(media.version, flow { emit(Unit) }) { _, _ -> }.map { media.trashed() }
        key.startsWith(SOURCE_ALBUM) -> {
            val bucket = key.removePrefix(SOURCE_ALBUM).toLongOrNull()
            media.media.map { list -> list.filter { it.bucketId == bucket } }
        }
        else -> {
            val ids = custom.getOrPut(key) { MutableStateFlow(emptyList()) }
            combine(ids, media.media) { order, list ->
                val byId = list.associateBy { it.id }
                order.mapNotNull { byId[it] }
            }
        }
    }

    companion object {
        const val SOURCE_TIMELINE = "timeline"
        const val SOURCE_FAVORITES = "favorites"
        const val SOURCE_VIDEOS = "videos"
        const val SOURCE_SCREENSHOTS = "screenshots"
        const val SOURCE_TRASH = "trash"
        const val SOURCE_LARGE = "large"
        const val SOURCE_ALBUM = "album:"

        fun album(id: Long) = "$SOURCE_ALBUM$id"
    }
}
