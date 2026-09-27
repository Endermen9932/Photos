package app.lumen.photos.edit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.lumen.photos.ai.ModelManager
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.data.media.MediaRepository
import app.lumen.photos.data.settings.AppSettings
import app.lumen.photos.data.settings.SettingsRepository
import app.lumen.photos.optimize.ExifCopier
import app.lumen.photos.work.UpscaleWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

data class UpscaleProgress(
    val mediaId: Long,
    val state: WorkInfo.State,
    val done: Int,
    val total: Int,
    val secondsLeft: Long,
    /** Name of the saved copy once finished. */
    val resultName: String?,
    val error: String?,
)

/** Plan for one upscale: the size the model works on and the size of the result. */
data class UpscalePlan(val inputWidth: Int, val inputHeight: Int, val factor: Int, val limited: Boolean) {
    val outputWidth get() = inputWidth * factor
    val outputHeight get() = inputHeight * factor
    val inputMegapixels get() = inputWidth.toLong() * inputHeight / 1_000_000f
}

/** Upscaling and the AI editor: model selection, background jobs and saving results. */
class ToolRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val models: ModelManager,
    private val media: MediaRepository,
    scope: CoroutineScope,
) {
    private val workManager = WorkManager.getInstance(context)
    private val resolver = context.contentResolver

    private val actives: Map<ToolKind, StateFlow<ToolModel?>> = ToolKind.entries.associateWith { kind ->
        settings.settings.map { ToolModelCatalog.byId(idOf(it, kind)) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, ToolModelCatalog.byId(idOf(settings.current, kind)))
    }

    private fun idOf(s: AppSettings, kind: ToolKind) = when (kind) {
        ToolKind.UPSCALE -> s.activeUpscaleModelId
        ToolKind.INPAINT -> s.activeInpaintModelId
        ToolKind.SEGMENT -> s.activeSegmentModelId
    }

    fun active(kind: ToolKind): StateFlow<ToolModel?> = actives.getValue(kind)

    /** The active model of [kind] if it is downloaded, otherwise any downloaded one. */
    fun usable(kind: ToolKind): ToolModel? =
        active(kind).value?.takeIf { models.isInstalled(it) } ?: ToolModelCatalog.of(kind).lastOrNull { models.isInstalled(it) }

    suspend fun setActive(kind: ToolKind, model: ToolModel?) = settings.update {
        when (kind) {
            ToolKind.UPSCALE -> it.copy(activeUpscaleModelId = model?.id)
            ToolKind.INPAINT -> it.copy(activeInpaintModelId = model?.id)
            ToolKind.SEGMENT -> it.copy(activeSegmentModelId = model?.id)
        }
    }

    suspend fun delete(model: ToolModel) {
        models.delete(model)
        if (active(model.kind).value == model) setActive(model.kind, ToolModelCatalog.of(model.kind).lastOrNull { models.isInstalled(it) })
    }

    // ------------------------------------------------------------------ upscaling

    fun plan(item: MediaItem, factor: Int): UpscalePlan {
        val w = item.displayWidth.coerceAtLeast(1)
        val h = item.displayHeight.coerceAtLeast(1)
        val outPixels = w.toLong() * h * factor * factor
        if (outPixels <= MAX_OUTPUT_PIXELS && maxOf(w, h) * factor <= MAX_OUTPUT_EDGE) return UpscalePlan(w, h, factor, false)
        val k = minOf(sqrt(MAX_OUTPUT_PIXELS.toDouble() / outPixels), MAX_OUTPUT_EDGE.toDouble() / (maxOf(w, h) * factor))
        return UpscalePlan((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), factor, true)
    }

    fun estimateSeconds(model: ToolModel, plan: UpscalePlan): Long = (plan.inputMegapixels * model.seconds).toLong().coerceAtLeast(2)

    fun startUpscale(item: MediaItem, model: ToolModel, factor: Int) {
        val request = OneTimeWorkRequestBuilder<UpscaleWorker>()
            .setInputData(
                workDataOf(UpscaleWorker.KEY_MEDIA to item.id, UpscaleWorker.KEY_MODEL to model.id, UpscaleWorker.KEY_FACTOR to factor)
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(UpscaleWorker.TAG)
            .addTag("media:${item.id}")
            .build()
        workManager.enqueueUniqueWork(UpscaleWorker.NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancelUpscale() = workManager.cancelUniqueWork(UpscaleWorker.NAME)

    /** Latest upscale job (running or finished) per photo. */
    val upscales: Flow<Map<Long, UpscaleProgress>> = workManager.getWorkInfosByTagFlow(UpscaleWorker.TAG).map { infos ->
        infos.mapNotNull { info ->
            val id = info.tags.firstOrNull { it.startsWith("media:") }?.removePrefix("media:")?.toLongOrNull()
                ?: info.outputData.getLong(UpscaleWorker.KEY_MEDIA, -1).takeIf { it >= 0 }
                ?: info.progress.getLong(UpscaleWorker.KEY_MEDIA, -1).takeIf { it >= 0 }
                ?: return@mapNotNull null
            id to UpscaleProgress(
                mediaId = id,
                state = info.state,
                done = info.progress.getInt(UpscaleWorker.KEY_DONE, 0),
                total = info.progress.getInt(UpscaleWorker.KEY_TOTAL, 0),
                secondsLeft = info.progress.getLong(UpscaleWorker.KEY_ETA, -1),
                resultName = info.outputData.getString(UpscaleWorker.KEY_NAME),
                error = info.outputData.getString(UpscaleWorker.KEY_ERROR),
            )
        }.toMap()
    }

    // ------------------------------------------------------------------ images

    /** Decodes [item] upright and mutable, at most [maxPixels] large (scaled evenly). */
    fun decode(item: MediaItem, maxPixels: Long = MAX_EDIT_PIXELS): Bitmap {
        val source = ImageDecoder.createSource(resolver, item.uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val w = info.size.width
            val h = info.size.height
            if (w.toLong() * h > maxPixels) {
                val k = sqrt(maxPixels.toDouble() / (w.toLong() * h))
                decoder.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
            }
        }
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, true).also { bitmap.recycle() }
    }

    private fun readMetadata(item: MediaItem): Map<String, String> =
        runCatching { resolver.openInputStream(MediaStore.setRequireOriginal(item.uri))?.use { ExifCopier.readAttributes(it) } }.getOrNull()
            ?: runCatching { resolver.openInputStream(item.uri)?.use { ExifCopier.readAttributes(it) } }.getOrNull().orEmpty()

    /**
     * Saves [bitmap] as a new photo next to [item] (same folder and date, metadata copied), so
     * the original always stays untouched. PNG keeps transparency (cut-outs).
     */
    suspend fun saveCopy(item: MediaItem, bitmap: Bitmap, suffix: String, png: Boolean = false): SavedCopy = withContext(Dispatchers.IO) {
        val bytes = ByteArrayOutputStream(bitmap.width * bitmap.height / 3).use { out ->
            check(bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, out)) { "Kodierung fehlgeschlagen" }
            out.toByteArray()
        }
        val data = if (png) bytes else ExifCopier.spliceIntoJpeg(bytes, readMetadata(item), bitmap.width, bitmap.height, context.cacheDir)
        val name = "${item.name.substringBeforeLast('.')}_$suffix.${if (png) "png" else "jpg"}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, item.relativePath.ifBlank { "Pictures/" })
            put(MediaStore.MediaColumns.DATE_TAKEN, item.timestamp)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Datei konnte nicht angelegt werden")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(data) } ?: error("Kein Schreibzugriff")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        media.refresh()
        SavedCopy(uri, name, runCatching { android.content.ContentUris.parseId(uri) }.getOrDefault(-1))
    }

    data class SavedCopy(val uri: Uri, val name: String, val id: Long)

    companion object {
        /** Upscaled results are limited to 40 MP (a 160 MB bitmap while encoding). */
        const val MAX_OUTPUT_PIXELS = 40_000_000L
        const val MAX_OUTPUT_EDGE = 10_000

        /** The AI editor works on at most 12.6 MP (a full Pixel photo) to stay within memory. */
        const val MAX_EDIT_PIXELS = 12_600_000L
    }
}
