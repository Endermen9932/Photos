package app.lumen.photos.optimize

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.provider.MediaStore
import app.lumen.photos.data.db.OptimizedDao
import app.lumen.photos.data.db.OptimizedEntity
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.data.settings.OptimizeMode
import app.lumen.photos.data.settings.OptimizerSettings
import app.lumen.photos.data.settings.OutputFormat
import app.lumen.photos.data.settings.TargetResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Compressed(val bytes: ByteArray, val width: Int, val height: Int, val hasGainmap: Boolean, val format: OutputFormat)

data class PreviewResult(
    val item: MediaItem,
    val original: Bitmap,
    val compressed: Bitmap,
    val originalBytes: Long,
    val compressedBytes: Long,
    val width: Int,
    val height: Int,
    val hasGainmap: Boolean,
)

data class Estimate(
    val candidates: Int,
    val totalBytes: Long,
    val projectedBytes: Long,
    val sampled: Int,
) {
    val savedBytes: Long get() = (totalBytes - projectedBytes).coerceAtLeast(0)
    val savedFraction: Float get() = if (totalBytes > 0) savedBytes.toFloat() / totalBytes else 0f
}

sealed interface Outcome {
    data class Saved(val before: Long, val after: Long) : Outcome
    data class Skipped(val reason: String) : Outcome
    data class Failed(val error: String) : Outcome
}

/**
 * Downscales and re-encodes photos. Keeps capture date, camera data and location, and – for JPEG –
 * the Ultra HDR gain map of Pixel photos.
 */
class ImageOptimizer(private val context: Context, private val dao: OptimizedDao) {

    private val resolver = context.contentResolver

    companion object {
        private val OTHER_FORMATS = setOf("image/png", "image/webp", "image/heic", "image/heif", "image/avif")

        /** Returns the size an image of [w]×[h] gets for [res], or null if it already fits. */
        fun targetSize(w: Int, h: Int, res: TargetResolution): Pair<Int, Int>? {
            if (w <= 0 || h <= 0 || res == TargetResolution.ORIGINAL) return null
            val long = max(w, h)
            val short = min(w, h)
            val scale = min(res.longEdge.toFloat() / long, res.shortEdge.toFloat() / short)
            if (scale >= 1f) return null
            return (w * scale).roundToInt().coerceAtLeast(1) to (h * scale).roundToInt().coerceAtLeast(1)
        }

        fun isCandidate(item: MediaItem, s: OptimizerSettings, done: Set<Long>): Boolean {
            if (!item.isImage || item.id in done) return false
            if (s.skipFavorites && item.isFavorite) return false
            val jpeg = item.mimeType == "image/jpeg"
            if (!jpeg && !(s.includeOtherFormats && item.mimeType in OTHER_FORMATS)) return false
            if (item.width <= 0 || item.height <= 0) return s.recompressSmaller && item.size > 1_000_000
            return targetSize(item.width, item.height, s.resolution) != null || s.recompressSmaller
        }

        /** Rough guess before sampling: bytes scale with pixel count, JPEG quality adds a factor. */
        fun heuristicRatio(item: MediaItem, s: OptimizerSettings): Float {
            val target = targetSize(item.width, item.height, s.resolution)
            val pixelRatio = target?.let { (it.first.toFloat() * it.second) / (item.width.toFloat() * item.height) } ?: 1f
            val q = s.quality / 100f
            val qualityFactor = 0.25f + 0.75f * q * q * q
            return (pixelRatio * qualityFactor * 1.4f).coerceIn(0.03f, 1f)
        }
    }

    suspend fun optimizedIds(): Set<Long> = dao.ids().toSet()

    private fun decode(item: MediaItem, s: OptimizerSettings, mutable: Boolean = false): Bitmap {
        val source = ImageDecoder.createSource(resolver, item.uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = mutable || !s.keepUltraHdr
            targetSize(info.size.width, info.size.height, s.resolution)?.let { (w, h) -> decoder.setTargetSize(w, h) }
        }
    }

    /** Encodes [item] with the given settings, entirely in memory. */
    suspend fun compress(item: MediaItem, s: OptimizerSettings): Compressed = withContext(Dispatchers.Default) {
        val bitmap = decode(item, s)
        try {
            if (!s.keepUltraHdr && bitmap.hasGainmap()) runCatching { bitmap.gainmap = null }
            val format = effectiveFormat(item, s)
            val out = ByteArrayOutputStream((item.size / 3).toInt().coerceIn(64 * 1024, 32 * 1024 * 1024))
            val cf = if (format == OutputFormat.WEBP) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
            check(bitmap.compress(cf, s.quality, out)) { "Kodierung fehlgeschlagen" }
            Compressed(out.toByteArray(), bitmap.width, bitmap.height, bitmap.hasGainmap() && format == OutputFormat.JPEG, format)
        } finally {
            bitmap.recycle()
        }
    }

    private fun effectiveFormat(item: MediaItem, s: OptimizerSettings): OutputFormat =
        if (s.mode == OptimizeMode.REPLACE && item.mimeType == "image/jpeg") OutputFormat.JPEG else s.format

    /** Builds a before/after pair for the preview screen. */
    suspend fun preview(item: MediaItem, s: OptimizerSettings, maxOriginalEdge: Int = 4096): PreviewResult =
        withContext(Dispatchers.Default) {
            val compressed = compress(item, s)
            val compressedBitmap = BitmapFactory.decodeByteArray(compressed.bytes, 0, compressed.bytes.size)
                ?: error("Vorschau konnte nicht dekodiert werden")
            val original = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = min(1f, maxOriginalEdge.toFloat() / max(w, h))
                if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
            }
            PreviewResult(
                item = item,
                original = original,
                compressed = compressedBitmap,
                originalBytes = item.size,
                compressedBytes = compressed.bytes.size.toLong(),
                width = compressed.width,
                height = compressed.height,
                hasGainmap = compressed.hasGainmap,
            )
        }

    /** Compresses a spread-out sample of [candidates] to extrapolate the total savings. */
    suspend fun estimate(
        candidates: List<MediaItem>,
        s: OptimizerSettings,
        samples: Int = 8,
        onProgress: (Int) -> Unit = {},
    ): Estimate = withContext(Dispatchers.Default) {
        val total = candidates.sumOf { it.size }
        if (candidates.isEmpty()) return@withContext Estimate(0, 0, 0, 0)
        val picks = if (candidates.size <= samples) candidates
        else List(samples) { i -> candidates[(i * candidates.size) / samples + (candidates.size / samples) / 2] }
        var sampleOriginal = 0L
        var sampleNew = 0L
        var n = 0
        for (item in picks) {
            runCatching { compress(item, s) }.getOrNull()?.let {
                sampleOriginal += item.size
                sampleNew += minOf(it.bytes.size.toLong(), item.size)
                n++
            }
            onProgress(n)
        }
        val ratio = if (sampleOriginal > 0) sampleNew.toDouble() / sampleOriginal
        else candidates.map { heuristicRatio(it, s).toDouble() }.average()
        Estimate(candidates.size, total, (total * ratio).toLong(), n)
    }

    /**
     * Optimises one photo. Requires write access to [item] (granted through a MediaStore write
     * request or the "media management" permission).
     */
    suspend fun apply(item: MediaItem, s: OptimizerSettings): Outcome = withContext(Dispatchers.IO) {
        try {
            val attributes = if (s.keepMetadata) readMetadata(item) else emptyMap()
            val compressed = compress(item, s)
            val minSize = item.size * (100 - s.minSavingsPercent) / 100
            if (compressed.bytes.size >= minSize) {
                dao.upsert(OptimizedEntity(item.id, item.size, item.size, item.width, item.height, System.currentTimeMillis()))
                return@withContext Outcome.Skipped("Zu wenig Ersparnis")
            }

            val finalBytes: ByteArray = when (compressed.format) {
                OutputFormat.JPEG -> ExifCopier.spliceIntoJpeg(compressed.bytes, attributes, compressed.width, compressed.height, context.cacheDir)
                OutputFormat.WEBP -> {
                    val tmp = File.createTempFile("opt", ".webp", context.cacheDir)
                    try {
                        tmp.writeBytes(compressed.bytes)
                        ExifCopier.writeToFile(tmp, attributes, compressed.width, compressed.height)
                        tmp.readBytes()
                    } finally {
                        tmp.delete()
                    }
                }
            }
            // Sanity check: the new file must be decodable before we touch the original.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(finalBytes, 0, finalBytes.size, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Ungültiges Ergebnis" }

            val replaceInPlace = s.mode == OptimizeMode.REPLACE && item.mimeType == compressed.format.mime
            if (replaceInPlace) {
                resolver.openOutputStream(item.uri, "wt")?.use { it.write(finalBytes) } ?: error("Kein Schreibzugriff")
            } else {
                writeCopyAndTrashOriginal(item, finalBytes, compressed.format)
            }
            dao.upsert(
                OptimizedEntity(item.id, item.size, finalBytes.size.toLong(), compressed.width, compressed.height, System.currentTimeMillis())
            )
            Outcome.Saved(item.size, finalBytes.size.toLong())
        } catch (e: SecurityException) {
            Outcome.Failed("Keine Berechtigung")
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: OutOfMemoryError) {
            Outcome.Failed("Nicht genug Speicher")
        }
    }

    /**
     * Reads EXIF from the unredacted original so GPS coordinates survive (needs
     * ACCESS_MEDIA_LOCATION; without it Android hides the location and it would get lost).
     */
    private fun readMetadata(item: MediaItem): Map<String, String> {
        val original = runCatching {
            resolver.openInputStream(MediaStore.setRequireOriginal(item.uri))?.use { ExifCopier.readAttributes(it) }
        }.getOrNull()
        return original ?: runCatching {
            resolver.openInputStream(item.uri)?.use { ExifCopier.readAttributes(it) }
        }.getOrNull().orEmpty()
    }

    private fun writeCopyAndTrashOriginal(item: MediaItem, bytes: ByteArray, format: OutputFormat) {
        val baseName = item.name.substringBeforeLast('.')
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.lumen-tmp.${format.extension}")
            put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, item.relativePath.ifBlank { "Pictures/" })
            put(MediaStore.MediaColumns.DATE_TAKEN, item.timestamp)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val newUri = resolver.insert(collection, values) ?: error("Datei konnte nicht angelegt werden")
        try {
            resolver.openOutputStream(newUri, "w")?.use { it.write(bytes) } ?: error("Kein Schreibzugriff")
            // Move the original to the trash (restorable for 30 days), then give the copy its name.
            val trashed = resolver.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
            check(trashed > 0) { "Original konnte nicht in den Papierkorb verschoben werden" }
            resolver.update(
                newUri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.${format.extension}")
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null, null
            )
        } catch (e: Exception) {
            resolver.delete(newUri, null, null)
            throw e
        }
    }
}
