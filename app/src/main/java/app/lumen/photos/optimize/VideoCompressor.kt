package app.lumen.photos.optimize

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.lumen.photos.data.db.OptimizedDao
import app.lumen.photos.data.db.OptimizedEntity
import app.lumen.photos.data.media.MediaItem as LumenItem
import app.lumen.photos.data.settings.OptimizeMode
import app.lumen.photos.data.settings.TargetResolution
import app.lumen.photos.data.settings.VideoCodec
import app.lumen.photos.data.settings.VideoOptimizerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class VideoInfo(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bitrate: Long,
    val frameRate: Float,
    val hdr: Boolean,
    val location: Pair<Float, Float>?,
    val captureTimeMs: Long?,
)

data class VideoPreview(
    val item: LumenItem,
    val original: Bitmap,
    val compressed: Bitmap,
    val originalBitrate: Long,
    val newBitrate: Long,
    val width: Int,
    val height: Int,
    val estimatedSize: Long,
)

/**
 * Re-encodes videos with the phone's hardware encoder (Media3 Transformer, no Google services):
 * scales down, lowers the bitrate and keeps capture date, GPS position and (with HEVC) HDR.
 */
@OptIn(UnstableApi::class)
class VideoCompressor(private val context: Context, private val dao: OptimizedDao) {

    private val resolver = context.contentResolver

    companion object {
        private val SUPPORTED = setOf("video/mp4", "video/quicktime", "video/3gpp", "video/x-matroska", "video/webm")
        private const val AUDIO_BITRATE = 192_000L

        /** Bits per pixel and frame for the quality presets (H.264; HEVC needs ~40 % less). */
        fun bitsPerPixel(quality: Int): Float = 0.015f + 0.11f * (quality / 100f) * (quality / 100f)

        fun targetSize(w: Int, h: Int, res: TargetResolution): Pair<Int, Int> {
            if (w <= 0 || h <= 0 || res == TargetResolution.ORIGINAL) return w to h
            val short = min(w, h)
            if (short <= res.shortEdge) return w to h
            val scale = res.shortEdge.toFloat() / short
            // Encoders need even dimensions.
            return ((w * scale).roundToInt() / 2 * 2) to ((h * scale).roundToInt() / 2 * 2)
        }

        fun targetBitrate(w: Int, h: Int, fps: Float, s: VideoOptimizerSettings): Long {
            val (tw, th) = targetSize(w, h, s.resolution)
            val codecFactor = if (s.codec == VideoCodec.HEVC) 0.6f else 1f
            return (tw.toLong() * th * fps.coerceIn(15f, 120f) * bitsPerPixel(s.quality) * codecFactor).toLong()
                .coerceIn(400_000L, 80_000_000L)
        }

        /** Estimated output size from the video's metadata in MediaStore (no decoding needed). */
        fun estimateSize(item: LumenItem, s: VideoOptimizerSettings, fps: Float = 30f): Long {
            val bitrate = targetBitrate(item.displayWidth, item.displayHeight, fps, s)
            return (bitrate + AUDIO_BITRATE) * item.durationMs / 8000
        }

        fun isCandidate(item: LumenItem, s: VideoOptimizerSettings, done: Set<Long>): Boolean {
            if (!item.isVideo || item.id in done || item.durationMs < 1000) return false
            if (s.skipFavorites && item.isFavorite) return false
            if (item.mimeType !in SUPPORTED) return false
            val estimate = estimateSize(item, s)
            return estimate < item.size * (100 - s.minSavingsPercent) / 100
        }
    }

    fun info(item: LumenItem): VideoInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, runCatching { MediaStore.setRequireOriginal(item.uri) }.getOrDefault(item.uri))
        } catch (e: Exception) {
            r.setDataSource(context, item.uri)
        }
        try {
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: item.width
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: item.height
            if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
            val transfer = if (Build.VERSION.SDK_INT >= 30) r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull() else null
            val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: item.durationMs
            val fps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                ?: frames?.let { if (duration > 0) it * 1000f / duration else null } ?: 30f
            return VideoInfo(
                width = w,
                height = h,
                durationMs = duration,
                bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                    ?: (item.size * 8000 / max(1, duration)),
                frameRate = fps.coerceIn(1f, 240f),
                hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG,
                location = parseLocation(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)),
                captureTimeMs = parseDate(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)),
            )
        } finally {
            r.release()
        }
    }

    private fun parseLocation(iso6709: String?): Pair<Float, Float>? {
        if (iso6709 == null) return null
        val m = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(iso6709) ?: return null
        val lat = m.groupValues[1].toFloatOrNull() ?: return null
        val lon = m.groupValues[2].toFloatOrNull() ?: return null
        return if (lat in -90f..90f && lon in -180f..180f && !(lat == 0f && lon == 0f)) lat to lon else null
    }

    private fun parseDate(s: String?): Long? {
        if (s == null) return null
        for (pattern in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss")) {
            runCatching {
                val f = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val t = f.parse(s)?.time
                if (t != null && t > 0) return t
            }
        }
        return null
    }

    /** Runs a Media3 export on the main looper and suspends until it is done. */
    private suspend fun export(
        item: LumenItem,
        info: VideoInfo,
        s: VideoOptimizerSettings,
        output: File,
        clip: LongRange? = null,
        onProgress: (Int) -> Unit = {},
    ): ExportResult = withContext(Dispatchers.Main) {
        val mediaItem = MediaItem.Builder().setUri(item.uri).apply {
            if (clip != null) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.first)
                        .setEndPositionMs(clip.last)
                        .build()
                )
            }
        }.build()
        val (tw, th) = targetSize(info.width, info.height, s.resolution)
        val effects = if (tw != info.width || th != info.height) {
            Effects(emptyList(), listOf(Presentation.createForShortSide(min(tw, th))))
        } else Effects.EMPTY
        val edited = EditedMediaItem.Builder(mediaItem).setEffects(effects).build()
        val keepHdr = info.hdr && s.keepHdr && s.codec == VideoCodec.HEVC
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(listOf(edited)).build())
            .setHdrMode(if (!info.hdr || keepHdr) Composition.HDR_MODE_KEEP_HDR else Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        val encoder = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(targetBitrate(info.width, info.height, info.frameRate, s).toInt()).build()
            )
            .setEnableFallback(true)
            .build()
        val muxer = InAppMp4Muxer.Factory { entries ->
            if (s.keepMetadata) {
                info.location?.let { (lat, lon) -> entries.add(Mp4LocationData(lat, lon)) }
                val t = Mp4TimestampData.unixTimeToMp4TimeSeconds(info.captureTimeMs ?: item.timestamp)
                entries.removeAll { it is Mp4TimestampData }
                entries.add(Mp4TimestampData(t, t))
            }
        }
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(if (s.codec == VideoCodec.HEVC) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoder)
                .setMuxerFactory(muxer)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(exportResult)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            cont.invokeOnCancellation { transformer.cancel() }
            transformer.start(composition, output.absolutePath)
            // Poll progress on the main looper while exporting.
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 500)
                }
            }
            poll.run()
        }
    }

    /** Encodes a 3 s clip from the middle and returns a matching frame pair for the comparison. */
    suspend fun preview(item: LumenItem, s: VideoOptimizerSettings): VideoPreview = withContext(Dispatchers.IO) {
        val info = info(item)
        val start = (info.durationMs / 2 - 1500).coerceAtLeast(0)
        val end = min(info.durationMs, start + 3000)
        val out = File(context.cacheDir, "video-preview.mp4").apply { delete() }
        export(item, info, s, out, clip = start..end)
        val frameAt = 1_000_000L
        val original = MediaMetadataRetriever().run {
            setDataSource(context, item.uri)
            val b = getFrameAtTime((start * 1000) + frameAt, MediaMetadataRetriever.OPTION_CLOSEST)
            release(); b
        } ?: error("Kein Vorschaubild")
        val compressed = MediaMetadataRetriever().run {
            setDataSource(out.absolutePath)
            val b = getFrameAtTime(frameAt, MediaMetadataRetriever.OPTION_CLOSEST)
            release(); b
        } ?: error("Kein Vorschaubild")
        val clipMs = max(1L, end - start)
        val newBitrate = out.length() * 8000 / clipMs
        val (tw, th) = targetSize(info.width, info.height, s.resolution)
        out.delete()
        VideoPreview(
            item = item,
            original = original,
            compressed = compressed,
            originalBitrate = info.bitrate,
            newBitrate = newBitrate,
            width = tw,
            height = th,
            estimatedSize = newBitrate * info.durationMs / 8000,
        )
    }

    /** Compresses one video. Requires write access to it. */
    suspend fun apply(item: LumenItem, s: VideoOptimizerSettings, onProgress: (Int) -> Unit = {}): Outcome = coroutineScope {
        val out = File(context.cacheDir, "video-${item.id}.mp4").apply { delete() }
        try {
            val info = withContext(Dispatchers.IO) { info(item) }
            export(item, info, s, out, onProgress = onProgress)
            val newSize = out.length()
            if (newSize <= 0 || newSize >= item.size * (100 - s.minSavingsPercent) / 100) {
                dao.upsert(OptimizedEntity(item.id, item.size, item.size, item.width, item.height, System.currentTimeMillis()))
                return@coroutineScope Outcome.Skipped("Zu wenig Ersparnis")
            }
            withContext(Dispatchers.IO) {
                val replace = s.mode == OptimizeMode.REPLACE && item.mimeType == "video/mp4"
                if (replace) {
                    resolver.openOutputStream(item.uri, "wt")?.use { o -> out.inputStream().use { it.copyTo(o, 1 shl 16) } }
                        ?: error("Kein Schreibzugriff")
                    // Keep the position in the timeline even if the scanner reads a different date.
                    launch {
                        delay(1500)
                        runCatching {
                            resolver.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, item.timestamp) }, null, null)
                        }
                    }
                } else {
                    copyAndTrash(item, out)
                }
            }
            dao.upsert(OptimizedEntity(item.id, item.size, newSize, item.width, item.height, System.currentTimeMillis()))
            Outcome.Saved(item.size, newSize)
        } catch (e: SecurityException) {
            Outcome.Failed("Keine Berechtigung")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            out.delete()
        }
    }

    private fun copyAndTrash(item: LumenItem, file: File) {
        val base = item.name.substringBeforeLast('.')
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.lumen-tmp.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, item.relativePath.ifBlank { "Movies/" })
            put(MediaStore.MediaColumns.DATE_TAKEN, item.timestamp)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Datei konnte nicht angelegt werden")
        try {
            resolver.openOutputStream(uri, "w")?.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 16) } } ?: error("Kein Schreibzugriff")
            val trashed = resolver.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
            check(trashed > 0) { "Original konnte nicht in den Papierkorb verschoben werden" }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.mp4")
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
