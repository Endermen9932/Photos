package app.lumen.photos.ui.components

import android.content.Context
import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options

/**
 * Thumbnail request served from the MediaStore thumbnail cache. Much faster than decoding the
 * full-size JPEG for every grid cell and also works for videos.
 */
data class Thumb(val uri: Uri, val size: Int, val version: Long)

class ThumbFetcher(private val data: Thumb, private val context: Context) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bitmap = context.contentResolver.loadThumbnail(data.uri, android.util.Size(data.size, data.size), null)
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val context: Context) : Fetcher.Factory<Thumb> {
        override fun create(data: Thumb, options: Options, imageLoader: ImageLoader): Fetcher = ThumbFetcher(data, context)
    }
}

class ThumbKeyer : Keyer<Thumb> {
    override fun key(data: Thumb, options: Options): String = "thumb:${data.uri}:${data.size}:${data.version}"
}
