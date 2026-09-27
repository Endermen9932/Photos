package app.lumen.photos.ui.screens.duplicates

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lumen.photos.AppContainer
import app.lumen.photos.container
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.ui.components.BackButton
import app.lumen.photos.ui.components.ConnectedToggleGroup
import app.lumen.photos.ui.components.EmptyState
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.MediaThumbnail
import app.lumen.photos.ui.navigation.LocalNavigator
import app.lumen.photos.ui.rememberMediaActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

enum class DupMode { EXACT, SIMILAR }

class DuplicatesViewModel(private val c: AppContainer) : ViewModel() {
    private val _groups = MutableStateFlow<List<List<MediaItem>>?>(null)
    val groups = _groups.asStateFlow()
    private val _mode = MutableStateFlow(DupMode.EXACT)
    val mode = _mode.asStateFlow()
    private val _threshold = MutableStateFlow(0.93f)
    val threshold = _threshold.asStateFlow()
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected = _selected.asStateFlow()
    private var job: Job? = null

    init { scan() }

    fun setMode(m: DupMode) { _mode.value = m; scan() }
    fun setThreshold(t: Float) { _threshold.value = t; scan(debounce = true) }

    fun toggle(id: Long) {
        _selected.value = if (id in _selected.value) _selected.value - id else _selected.value + id
    }

    fun scan(debounce: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            if (debounce) delay(300)
            _groups.value = null
            val media = c.media.media.value
            val groups = withContext(Dispatchers.IO) {
                if (_mode.value == DupMode.EXACT) exactGroups(media) else similarGroups(media)
            }
            _groups.value = groups
            // Pre-select everything except the best photo of each group.
            _selected.value = groups.flatMap { g -> g.drop(1).map { it.id } }.toSet()
        }
    }

    private fun best(group: List<MediaItem>): List<MediaItem> =
        group.sortedWith(
            compareByDescending<MediaItem> { it.isFavorite }
                .thenByDescending { it.width.toLong() * it.height }
                .thenByDescending { it.size }
                .thenBy { it.timestamp }
        )

    private fun exactGroups(media: List<MediaItem>): List<List<MediaItem>> {
        val resolver = c.appContext.contentResolver
        val bySize = media.groupBy { it.size }.values.filter { it.size > 1 }
        val result = ArrayList<List<MediaItem>>()
        for (sameSize in bySize) {
            val byHash = sameSize.groupBy { item ->
                runCatching {
                    resolver.openInputStream(item.uri)?.use { input ->
                        val md = MessageDigest.getInstance("SHA-256")
                        val buf = ByteArray(1 shl 16)
                        var total = 0
                        while (total < 4 * 1024 * 1024) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            total += n
                        }
                        md.digest().joinToString("") { "%02x".format(it) }
                    }
                }.getOrNull() ?: "id-${item.id}"
            }
            byHash.values.filter { it.size > 1 }.forEach { result += best(it) }
        }
        return result.sortedByDescending { g -> g.drop(1).sumOf { it.size } }
    }

    private suspend fun similarGroups(media: List<MediaItem>): List<List<MediaItem>> {
        val model = c.ai.activeModel.value ?: return emptyList()
        c.index.ensureLoaded(model.id)
        val byId = media.associateBy { it.id }
        val ids = c.index.nearDuplicateGroups(media.filter { it.isImage }.map { it.id to it.timestamp }, _threshold.value)
        return ids.map { g -> best(g.mapNotNull { byId[it] }) }
            .filter { it.size > 1 }
            .sortedByDescending { it.first().timestamp }
    }

    val aiReady: Boolean get() = c.ai.activeModel.value?.let { c.models.isInstalled(it) } == true
}

@Composable
fun DuplicatesScreen() {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val vm: DuplicatesViewModel = viewModel { DuplicatesViewModel(context.container) }
    val groups by vm.groups.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val threshold by vm.threshold.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val actions = rememberMediaActions()
    val scope = rememberCoroutineScope()
    val selectedItems = remember(groups, selected) { groups.orEmpty().flatten().filter { it.id in selected }.distinctBy { it.id } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Duplikate & Serien") }, navigationIcon = { BackButton { nav.back() } }) },
        bottomBar = {
            if (selectedItems.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Button(
                        onClick = { scope.launch { if (actions.trash(selectedItems)) vm.scan() } },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(60.dp)
                    ) {
                        Icon(Icons.Outlined.Delete, null)
                        Text("  ${selectedItems.size} löschen · ${Format.bytes(selectedItems.sumOf { it.size })} frei")
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ConnectedToggleGroup(
                options = DupMode.entries,
                selected = mode,
                onSelect = { vm.setMode(it) },
                label = { if (it == DupMode.EXACT) "Exakte Kopien" else "Ähnliche (KI)" },
                enabled = { it == DupMode.EXACT || vm.aiReady },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (mode == DupMode.SIMILAR) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("Ähnlichkeit ab ${(threshold * 100).toInt()} %", style = MaterialTheme.typography.labelLarge)
                    var t by remember(threshold) { mutableFloatStateOf(threshold) }
                    Slider(value = t, onValueChange = { t = it }, onValueChangeFinished = { vm.setThreshold(t) }, valueRange = 0.8f..0.99f)
                }
            }
            AnimatedContent(groups, label = "groups", modifier = Modifier.weight(1f)) { g ->
                when {
                    g == null -> Box(Modifier.fillMaxSize()) { LoadingIndicator(Modifier.align(Alignment.Center).size(72.dp)) }
                    g.isEmpty() -> EmptyState(
                        Icons.Outlined.ContentCopy,
                        "Keine Duplikate gefunden",
                        if (mode == DupMode.EXACT) "Keine byte-identischen Dateien in deiner Galerie." else "Keine sehr ähnlichen Fotos mit dieser Schwelle."
                    )
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Text(
                                "${g.size} Gruppen · Das beste Foto jeder Gruppe (★) bleibt erhalten, tippe auf ein Foto zum (Ab-)Wählen.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                        items(g, key = { it.first().id }) { group ->
                            Column(Modifier.animateItem().padding(vertical = 4.dp)) {
                                Text(
                                    "${Format.short(group.first().timestamp)} · ${group.size} Fotos · ${Format.bytes(group.sumOf { it.size })}",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                                )
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(group, key = { it.id }) { item ->
                                        val isSel = item.id in selected
                                        Surface(
                                            onClick = { vm.toggle(item.id) },
                                            shape = RoundedCornerShape(20.dp),
                                            border = if (isSel) BorderStroke(3.dp, MaterialTheme.colorScheme.error) else null,
                                            modifier = Modifier.size(width = 120.dp, height = 150.dp)
                                        ) {
                                            Box {
                                                MediaThumbnail(item, shared = false, size = 320, modifier = Modifier.fillMaxSize())
                                                if (item == group.first()) {
                                                    Icon(Icons.Outlined.Star, "Bestes Foto", tint = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
                                                }
                                                if (isSel) {
                                                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
                                                }
                                                Surface(
                                                    color = Color.Black.copy(alpha = 0.5f),
                                                    contentColor = Color.White,
                                                    shape = RoundedCornerShape(topEnd = 12.dp),
                                                    modifier = Modifier.align(Alignment.BottomStart)
                                                ) {
                                                    Text(
                                                        "${item.width}×${item.height}\n${Format.bytes(item.size)}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(6.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
