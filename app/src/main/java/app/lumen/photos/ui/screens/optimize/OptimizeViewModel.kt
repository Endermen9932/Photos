package app.lumen.photos.ui.screens.optimize

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.lumen.photos.AppContainer
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.data.settings.OptimizeMode
import app.lumen.photos.data.settings.OptimizerSettings
import app.lumen.photos.data.settings.OutputFormat
import app.lumen.photos.optimize.Estimate
import app.lumen.photos.optimize.ImageOptimizer
import app.lumen.photos.optimize.PreviewResult
import app.lumen.photos.work.OptimizeJob
import app.lumen.photos.work.OptimizeWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OptimizeProgress(
    val state: WorkInfo.State,
    val done: Int,
    val total: Int,
    val saved: Long,
    val failed: Int,
    val skipped: Int,
)

class OptimizeViewModel(private val c: AppContainer) : ViewModel() {
    private val workManager = WorkManager.getInstance(c.appContext)
    val settings: StateFlow<OptimizerSettings> = c.settings.settings.map { it.optimizer }
        .stateIn(viewModelScope, SharingStarted.Eagerly, c.settings.current.optimizer)

    private val doneIds = MutableStateFlow<Set<Long>>(emptySet())

    val candidates: StateFlow<List<MediaItem>> = combine(c.media.media, settings, doneIds) { media, s, done ->
        media.filter { ImageOptimizer.isCandidate(it, s, done) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _previewItem = MutableStateFlow<MediaItem?>(null)
    val previewItem = _previewItem.asStateFlow()
    private val _preview = MutableStateFlow<PreviewResult?>(null)
    val preview = _preview.asStateFlow()
    private val _previewLoading = MutableStateFlow(false)
    val previewLoading = _previewLoading.asStateFlow()
    private val _previewError = MutableStateFlow<String?>(null)
    val previewError = _previewError.asStateFlow()

    private val _estimate = MutableStateFlow<Estimate?>(null)
    val estimate = _estimate.asStateFlow()
    private val _estimating = MutableStateFlow(false)
    val estimating = _estimating.asStateFlow()

    val progress: StateFlow<OptimizeProgress?> = workManager.getWorkInfosForUniqueWorkFlow(OptimizeWorker.NAME)
        .map { infos ->
            val info = infos.firstOrNull() ?: return@map null
            val data = if (info.state.isFinished) info.outputData else info.progress
            OptimizeProgress(
                state = info.state,
                done = data.getInt(OptimizeWorker.KEY_DONE, 0),
                total = data.getInt(OptimizeWorker.KEY_TOTAL, 0),
                saved = data.getLong(OptimizeWorker.KEY_SAVED, 0),
                failed = data.getInt(OptimizeWorker.KEY_FAILED, 0),
                skipped = data.getInt(OptimizeWorker.KEY_SKIPPED, 0),
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var previewJob: Job? = null
    private var estimateJob: Job? = null

    init {
        viewModelScope.launch { doneIds.value = c.optimizer.optimizedIds() }
        viewModelScope.launch {
            combine(settings, candidates) { s, list -> s to list }.collect { (s, list) ->
                if (_previewItem.value == null || list.none { it.id == _previewItem.value?.id }) {
                    _previewItem.value = pickDefaultPreview(list)
                }
                schedulePreview(s)
                scheduleEstimate(s, list)
            }
        }
        viewModelScope.launch {
            progress.collect { p -> if (p?.state == WorkInfo.State.SUCCEEDED) doneIds.value = c.optimizer.optimizedIds() }
        }
    }

    /** Prefer a large, detailed camera photo for a meaningful preview. */
    private fun pickDefaultPreview(list: List<MediaItem>): MediaItem? =
        list.take(200).maxByOrNull { it.size }

    fun selectPreview(item: MediaItem) {
        _previewItem.value = item
        schedulePreview(settings.value, immediate = true)
    }

    private fun schedulePreview(s: OptimizerSettings, immediate: Boolean = false) {
        val item = _previewItem.value ?: run { _preview.value = null; return }
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            if (!immediate) delay(250)
            _previewLoading.value = true
            _previewError.value = null
            runCatching { c.optimizer.preview(item, s) }
                .onSuccess { _preview.value = it }
                .onFailure { _previewError.value = it.message }
            _previewLoading.value = false
        }
    }

    private fun scheduleEstimate(s: OptimizerSettings, list: List<MediaItem>) {
        estimateJob?.cancel()
        estimateJob = viewModelScope.launch {
            delay(700)
            _estimating.value = true
            _estimate.value = c.optimizer.estimate(list, s, samples = 10)
            _estimating.value = false
        }
    }

    fun update(transform: (OptimizerSettings) -> OptimizerSettings) {
        viewModelScope.launch {
            c.settings.updateOptimizer { s ->
                val next = transform(s)
                // WebP can't replace a JPEG in place.
                if (next.mode == OptimizeMode.REPLACE && next.format == OutputFormat.WEBP) next.copy(format = OutputFormat.JPEG) else next
            }
        }
    }

    /** Stores the job and starts the background worker. Write access must be granted before. */
    fun start(items: List<MediaItem>) {
        OptimizeWorker.writeJob(c.appContext, OptimizeJob(items.map { it.id }, settings.value))
        val request = OneTimeWorkRequestBuilder<OptimizeWorker>().build()
        workManager.enqueueUniqueWork(OptimizeWorker.NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() {
        workManager.cancelUniqueWork(OptimizeWorker.NAME)
    }

    fun dismissResult() {
        workManager.pruneWork()
    }
}
