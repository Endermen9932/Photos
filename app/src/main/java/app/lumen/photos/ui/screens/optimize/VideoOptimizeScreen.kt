package app.lumen.photos.ui.screens.optimize

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.lumen.photos.AppContainer
import app.lumen.photos.container
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.data.settings.OptimizeMode
import app.lumen.photos.data.settings.TargetResolution
import app.lumen.photos.data.settings.VideoCodec
import app.lumen.photos.data.settings.VideoOptimizerSettings
import app.lumen.photos.optimize.VideoCompressor
import app.lumen.photos.optimize.VideoPreview
import app.lumen.photos.ui.components.AnimatedNumber
import app.lumen.photos.ui.components.BackButton
import app.lumen.photos.ui.components.BeforeAfter
import app.lumen.photos.ui.components.ConnectedToggleGroup
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.MediaThumbnail
import app.lumen.photos.ui.components.SectionTitle
import app.lumen.photos.ui.components.ShapeIcon
import app.lumen.photos.ui.navigation.LocalNavigator
import app.lumen.photos.ui.rememberMediaActions
import app.lumen.photos.ui.theme.EmphasizedNumber
import app.lumen.photos.work.VideoOptimizeJob
import app.lumen.photos.work.VideoOptimizeWorker
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

private data class Preset(val label: String, val quality: Int)

private val presets = listOf(Preset("Max", 90), Preset("Hoch", 80), Preset("Gut", 70), Preset("Kompakt", 55), Preset("Klein", 40))

data class VideoProgress(val state: WorkInfo.State, val done: Int, val total: Int, val percent: Int, val saved: Long, val failed: Int, val skipped: Int)

class VideoOptimizeViewModel(private val c: AppContainer) : ViewModel() {
    private val wm = WorkManager.getInstance(c.appContext)
    val settings: StateFlow<VideoOptimizerSettings> = c.settings.settings.map { it.videoOptimizer }
        .stateIn(viewModelScope, SharingStarted.Eagerly, c.settings.current.videoOptimizer)
    private val done = MutableStateFlow<Set<Long>>(emptySet())
    val candidates: StateFlow<List<MediaItem>> = combine(c.media.media, settings, done) { media, s, d ->
        media.filter { VideoCompressor.isCandidate(it, s, d) }.sortedByDescending { it.size }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _previewItem = MutableStateFlow<MediaItem?>(null)
    val previewItem = _previewItem.asStateFlow()
    private val _preview = MutableStateFlow<VideoPreview?>(null)
    val preview = _preview.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var job: Job? = null

    val progress: StateFlow<VideoProgress?> = wm.getWorkInfosForUniqueWorkFlow(VideoOptimizeWorker.NAME).map { infos ->
        val info = infos.firstOrNull() ?: return@map null
        val d = if (info.state.isFinished) info.outputData else info.progress
        VideoProgress(
            info.state,
            d.getInt(VideoOptimizeWorker.KEY_DONE, 0), d.getInt(VideoOptimizeWorker.KEY_TOTAL, 0),
            d.getInt(VideoOptimizeWorker.KEY_PERCENT, 0), d.getLong(VideoOptimizeWorker.KEY_SAVED, 0),
            d.getInt(VideoOptimizeWorker.KEY_FAILED, 0), d.getInt(VideoOptimizeWorker.KEY_SKIPPED, 0),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { done.value = c.optimizer.optimizedIds() }
        viewModelScope.launch {
            combine(settings, candidates) { s, l -> s to l }.collect { (s, list) ->
                if (_previewItem.value == null || list.none { it.id == _previewItem.value?.id }) _previewItem.value = list.firstOrNull()
                schedulePreview(s)
            }
        }
    }

    fun select(item: MediaItem) {
        _previewItem.value = item
        schedulePreview(settings.value, immediate = true)
    }

    private fun schedulePreview(s: VideoOptimizerSettings, immediate: Boolean = false) {
        val item = _previewItem.value ?: run { _preview.value = null; return }
        job?.cancel()
        job = viewModelScope.launch {
            if (!immediate) delay(600)
            _loading.value = true
            _error.value = null
            runCatching { c.videoCompressor.preview(item, s) }
                .onSuccess { _preview.value = it }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) _error.value = it.message }
            _loading.value = false
        }
    }

    fun update(t: (VideoOptimizerSettings) -> VideoOptimizerSettings) = viewModelScope.launch {
        c.settings.updateVideoOptimizer { s -> t(s).let { if (it.codec == VideoCodec.H264) it.copy(keepHdr = false) else it } }
    }

    fun start(items: List<MediaItem>) {
        VideoOptimizeWorker.writeJob(c.appContext, VideoOptimizeJob(items.map { it.id }, settings.value))
        wm.enqueueUniqueWork(VideoOptimizeWorker.NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<VideoOptimizeWorker>().build())
    }

    fun cancel() = wm.cancelUniqueWork(VideoOptimizeWorker.NAME)
    fun dismiss() {
        wm.pruneWork()
        viewModelScope.launch { done.value = c.optimizer.optimizedIds() }
    }
}

@Composable
fun VideoOptimizeScreen() {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val vm: VideoOptimizeViewModel = viewModel { VideoOptimizeViewModel(context.container) }
    val actions = rememberMediaActions()
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val previewItem by vm.previewItem.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }

    val total = remember(candidates) { candidates.sumOf { it.size } }
    val estimated = remember(candidates, s) { candidates.sumOf { minOf(it.size, VideoCompressor.estimateSize(it, s)) } }
    val running = progress?.state == WorkInfo.State.RUNNING || progress?.state == WorkInfo.State.ENQUEUED

    Scaffold(
        topBar = { TopAppBar(title = { Text("Videos komprimieren") }, navigationIcon = { BackButton { nav.back() } }) },
        bottomBar = {
            if (!running && progress?.state != WorkInfo.State.SUCCEEDED) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                    Button(
                        onClick = { confirm = true },
                        enabled = candidates.isNotEmpty(),
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(64.dp)
                    ) {
                        Icon(Icons.Outlined.VideoSettings, null)
                        Spacer(Modifier.width(10.dp))
                        Text(if (candidates.isEmpty()) "Nichts zu komprimieren" else "${Format.count(candidates.size)} Videos komprimieren", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    ) { padding ->
        val p = progress
        if (p != null && (running || p.state == WorkInfo.State.SUCCEEDED)) {
            VideoProgressView(p, onCancel = { vm.cancel() }, onDone = { vm.dismiss() }, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text("Voraussichtlich frei", style = MaterialTheme.typography.titleMedium)
                    AnimatedNumber(total - estimated, { Format.bytes(it) }, EmphasizedNumber)
                    Text("${Format.count(candidates.size)} Videos · ${Format.bytes(total)} → ${Format.bytes(estimated)}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Berechnet aus Länge und Ziel-Bitrate. Videos, die kaum kleiner würden, werden ausgelassen.",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            SectionTitle("Vorschau (3-Sekunden-Testclip)")
            Box(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .aspectRatio(preview?.let { it.width.toFloat() / it.height }?.coerceIn(0.56f, 1.8f) ?: (16f / 9f))
            ) {
                val pr = preview
                when {
                    pr != null -> BeforeAfter(
                        before = remember(pr) { pr.original.asImageBitmap() },
                        after = remember(pr) { pr.compressed.asImageBitmap() },
                        beforeLabel = "Original · ${pr.originalBitrate / 1_000_000} Mbit/s",
                        afterLabel = "Neu · ${"%.1f".format(pr.newBitrate / 1_000_000f)} Mbit/s",
                        modifier = Modifier.fillMaxSize()
                    )
                    error != null -> Text("Vorschau nicht möglich: $error", Modifier.align(Alignment.Center).padding(20.dp))
                    candidates.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        ShapeIcon(Icons.Outlined.CheckCircle, size = 88.dp, shape = MaterialShapes.Sunny.toShape())
                        Text("Alle Videos sind schon kompakt", modifier = Modifier.padding(top = 10.dp))
                    }
                }
                if (loading) ContainedLoadingIndicator(Modifier.align(Alignment.Center).size(64.dp))
            }
            preview?.let { pr ->
                Text(
                    "${pr.item.displayWidth}×${pr.item.displayHeight} → ${pr.width}×${pr.height} · ${Format.bytes(pr.item.size)} → ca. ${Format.bytes(pr.estimatedSize)}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
            if (candidates.size > 1) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(candidates.take(40), key = { it.id }) { item ->
                        val sel = item.id == previewItem?.id
                        Surface(
                            onClick = { vm.select(item) },
                            shape = RoundedCornerShape(if (sel) 22.dp else 14.dp),
                            border = if (sel) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.size(72.dp)
                        ) { MediaThumbnail(item, shared = false, size = 192, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }

            SectionTitle("Zielauflösung")
            ConnectedToggleGroup(
                options = TargetResolution.entries,
                selected = s.resolution,
                onSelect = { r -> vm.update { it.copy(resolution = r) } },
                label = {
                    when (it) {
                        TargetResolution.HD -> "720p"; TargetResolution.FHD -> "1080p"; TargetResolution.QHD -> "1440p"
                        TargetResolution.UHD -> "4K"; TargetResolution.ORIGINAL -> "Original"
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            SectionTitle("Qualität", trailing = { Text("${s.quality} %", fontWeight = FontWeight.Bold) })
            ConnectedToggleGroup(
                options = presets,
                selected = presets.firstOrNull { it.quality == s.quality } ?: Preset("", -1),
                onSelect = { q -> vm.update { it.copy(quality = q.quality) } },
                label = { it.label },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            var q by remember(s.quality) { mutableStateOf(s.quality.toFloat()) }
            Slider(q, { q = it }, onValueChangeFinished = { vm.update { it.copy(quality = q.toInt()) } }, valueRange = 20f..100f, modifier = Modifier.padding(horizontal = 20.dp))

            SectionTitle("Codec")
            ConnectedToggleGroup(
                options = VideoCodec.entries,
                selected = s.codec,
                onSelect = { v -> vm.update { it.copy(codec = v) } },
                label = { it.label },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                if (s.codec == VideoCodec.HEVC) "HEVC braucht bei gleicher Qualität rund 40 % weniger Platz und erhält HDR. Empfohlen."
                else "H.264 spielt auf wirklich jedem Gerät ab, ist aber größer. HDR-Videos werden in SDR umgewandelt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )

            SectionTitle("Vorgehen")
            ConnectedToggleGroup(
                options = OptimizeMode.entries,
                selected = s.mode,
                onSelect = { m -> vm.update { it.copy(mode = m) } },
                label = { if (it == OptimizeMode.REPLACE) "Ersetzen" else "Kopie + Papierkorb" },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Toggle("HDR erhalten", "Nur mit HEVC möglich", s.keepHdr && s.codec == VideoCodec.HEVC) { v -> vm.update { it.copy(keepHdr = v) } }
            Toggle("Aufnahmedatum & Ort erhalten", "Schreibt Datum und GPS-Position ins neue Video", s.keepMetadata) { v -> vm.update { it.copy(keepMetadata = v) } }
            Toggle("Favoriten auslassen", null, s.skipFavorites) { v -> vm.update { it.copy(skipFavorites = v) } }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Outlined.Warning, null) },
            title = { Text("${Format.count(candidates.size)} Videos komprimieren?") },
            text = {
                Text(
                    "Voraussichtlich frei: ${Format.bytes(total - estimated)}\n\n" +
                        if (s.mode == OptimizeMode.REPLACE) "Die Originale werden überschrieben – das lässt sich nicht rückgängig machen. Mach vorher ein Backup, wenn du sie behalten willst."
                        else "Die Originale landen im Papierkorb und bleiben 30 Tage wiederherstellbar."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    scope.launch { if (actions.requestWrite(candidates)) vm.start(candidates) }
                }) { Text("Komprimieren") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirm = false; nav.backup() }) { Text("Erst Backup") }
                    TextButton(onClick = { confirm = false }) { Text("Abbrechen") }
                }
            }
        )
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

@Composable
private fun VideoProgressView(p: VideoProgress, onCancel: () -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val finished = p.state == WorkInfo.State.SUCCEEDED
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            if (finished) ShapeIcon(Icons.Outlined.CheckCircle, size = 200.dp, shape = MaterialShapes.Cookie12Sided.toShape(), spin = true)
            else {
                CircularWavyProgressIndicator(
                    progress = { if (p.total > 0) (p.done + p.percent / 100f) / p.total else 0f },
                    modifier = Modifier.size(220.dp)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${p.percent} %", style = EmphasizedNumber)
                    Text("Video ${minOf(p.done + 1, p.total)} von ${p.total}", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(if (finished) "Fertig!" else "Komprimiere Videos …", style = MaterialTheme.typography.headlineMedium)
        AnimatedNumber(p.saved, { "${Format.bytes(it)} freigegeben" }, MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        if (p.skipped > 0 || p.failed > 0) {
            Text(
                listOfNotNull(if (p.skipped > 0) "${p.skipped} übersprungen" else null, if (p.failed > 0) "${p.failed} fehlgeschlagen" else null).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!finished) Text("Läuft im Hintergrund weiter.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        if (finished) Button(onClick = onDone, shapes = ButtonDefaults.shapes()) { Text("Fertig") } else OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
    }
}
