package app.lumen.photos.ui.screens.timeline

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.MediaListRegistry
import app.lumen.photos.container
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.ui.LocalAppSettings
import app.lumen.photos.ui.components.EmptyState
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.MediaGrid
import app.lumen.photos.ui.components.MediaThumbnail
import app.lumen.photos.ui.components.SelectionBar
import app.lumen.photos.ui.navigation.LocalNavigator
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Memory(val yearsAgo: Int, val items: List<MediaItem>)

/** Photos taken around today's date in earlier years. */
fun computeMemories(items: List<MediaItem>): List<Memory> {
    val today = LocalDate.now()
    val byYear = HashMap<Int, MutableList<MediaItem>>()
    for (item in items) {
        if (!item.isImage) continue
        val d = Format.localDate(item.timestamp)
        val years = today.year - d.year
        if (years <= 0) continue
        val sameDay = runCatching { d.withYear(today.year) }.getOrNull() ?: continue
        if (kotlin.math.abs(ChronoUnit.DAYS.between(sameDay, today)) <= 3) byYear.getOrPut(years) { ArrayList() } += item
    }
    return byYear.entries.sortedBy { it.key }.map { Memory(it.key, it.value) }
}

@Composable
fun TimelineTab(gridState: LazyGridState, onSelectionModeChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    val scope = rememberCoroutineScope()
    val source = remember { c.lists.source(MediaListRegistry.SOURCE_TIMELINE, c.media, c.settings) }
    val items by source.collectAsStateWithLifecycle(initialValue = c.media.media.value)
    val loaded by c.media.loaded.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    val memories = remember(items) { computeMemories(items) }

    LaunchedEffect(selection.isNotEmpty()) { onSelectionModeChange(selection.isNotEmpty()) }
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        when {
            !loaded -> LoadingIndicator(Modifier.align(Alignment.Center).size(72.dp))
            items.isEmpty() -> EmptyState(
                Icons.Outlined.PhotoLibrary,
                "Noch keine Fotos",
                "Sobald du Fotos aufnimmst oder speicherst, erscheinen sie hier."
            )
            else -> MediaGrid(
                items = items,
                columns = settings.gridColumns,
                onColumnsChange = { n -> scope.launch { c.settings.update { it.copy(gridColumns = n) } } },
                selection = selection,
                onSelectionChange = { selection = it },
                onOpen = { nav.viewer(MediaListRegistry.SOURCE_TIMELINE, it.id) },
                state = gridState,
                contentPadding = PaddingValues(top = top + 64.dp, bottom = bottom + 96.dp),
                header = if (settings.showMemories && memories.isNotEmpty() && selection.isEmpty()) {
                    { MemoriesRow(memories) }
                } else null,
            )
        }

        // Top bar that floats over the grid.
        AnimatedContent(
            targetState = selection.isNotEmpty(),
            transitionSpec = {
                (fadeIn() + slideInVertically { -it / 2 }).togetherWith(fadeOut() + slideOutVertically { -it / 2 })
            },
            label = "topbar",
            modifier = Modifier.align(Alignment.TopCenter)
        ) { selecting ->
            if (selecting) {
                val selectedItems = remember(selection, items) { items.filter { it.id in selection } }
                SelectionBar(
                    selected = selectedItems,
                    onClear = { selection = emptySet() },
                    onSelectAll = { selection = items.mapTo(HashSet()) { it.id } },
                    modifier = Modifier.statusBarsPadding().padding(top = 4.dp)
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to MaterialTheme.colorScheme.surface,
                                0.7f to MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                1f to Color.Transparent
                            )
                        )
                        .statusBarsPadding()
                        .height(64.dp)
                        .padding(horizontal = 20.dp)
                ) {
                    Column(Modifier.align(Alignment.CenterStart)) {
                        Text("Lumen", style = MaterialTheme.typography.headlineMedium)
                        if (items.isNotEmpty()) {
                            Text(
                                "${Format.count(items.size)} Elemente",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    FilledTonalIconButton(onClick = { nav.settings() }, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Icon(Icons.Outlined.Settings, "Einstellungen")
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoriesRow(memories: List<Memory>) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(
            "Erinnerungen",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 10.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(memories, key = { it.yearsAgo }) { memory ->
                Surface(
                    onClick = {
                        val key = context.container.lists.register("memory:${memory.yearsAgo}", memory.items)
                        nav.viewer(key, memory.items.first().id)
                    },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.size(width = 150.dp, height = 200.dp)
                ) {
                    Box {
                        MediaThumbnail(
                            memory.items.first(), shared = false, showBadges = false, size = 512,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp))
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))
                            )
                        )
                        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                            Text(
                                if (memory.yearsAgo == 1) "Vor 1 Jahr" else "Vor ${memory.yearsAgo} Jahren",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Row {
                                Text("${memory.items.size} Fotos", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                            }
                        }
                    }
                }
            }
        }
    }
}

