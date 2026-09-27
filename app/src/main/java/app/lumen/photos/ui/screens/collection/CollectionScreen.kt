package app.lumen.photos.ui.screens.collection

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.MediaListRegistry
import app.lumen.photos.container
import app.lumen.photos.ui.components.BackButton
import app.lumen.photos.ui.components.EmptyState
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.Grouping
import app.lumen.photos.ui.components.MediaGrid
import app.lumen.photos.ui.components.SelectionBar
import app.lumen.photos.ui.navigation.LocalNavigator
import app.lumen.photos.ui.rememberMediaActions
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(source: String, title: String) {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val actions = rememberMediaActions()
    val scope = rememberCoroutineScope()
    val flow = remember(source) { c.lists.source(source, c.media, c.settings) }
    val items by flow.collectAsStateWithLifecycle(initialValue = null)
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var columns by remember { mutableStateOf(if (source.startsWith("search")) 3 else 4) }
    val isTrash = source == MediaListRegistry.SOURCE_TRASH
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(selection.isNotEmpty(), label = "bar") { selecting ->
                val list = items.orEmpty()
                if (selecting && !isTrash) {
                    SelectionBar(
                        selected = list.filter { it.id in selection },
                        onClear = { selection = emptySet() },
                        onSelectAll = { selection = list.mapTo(HashSet()) { it.id } },
                        modifier = Modifier.statusBarsPadding().padding(vertical = 6.dp)
                    )
                } else {
                    LargeFlexibleTopAppBar(
                        title = { Text(title) },
                        subtitle = {
                            items?.let { l ->
                                Text(
                                    if (isTrash) "${Format.count(l.size)} Elemente · werden nach 30 Tagen gelöscht"
                                    else "${Format.count(l.size)} Elemente · ${Format.bytes(l.sumOf { it.size })}"
                                )
                            }
                        },
                        navigationIcon = { BackButton { nav.back() } },
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
    ) { padding ->
        val list = items
        Box(Modifier.fillMaxSize()) {
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    Icons.Outlined.PhotoLibrary,
                    if (isTrash) "Papierkorb ist leer" else "Hier ist noch nichts",
                    if (isTrash) "Gelöschte Elemente bleiben hier 30 Tage, bevor sie endgültig entfernt werden." else "Diese Sammlung enthält keine Elemente.",
                    modifier = Modifier.padding(padding)
                )
                else -> MediaGrid(
                    items = list,
                    columns = columns,
                    onColumnsChange = { columns = it },
                    selection = selection,
                    onSelectionChange = { selection = it },
                    onOpen = { nav.viewer(source, it.id) },
                    grouping = if (source.startsWith("search") || source.startsWith("similar") || source == MediaListRegistry.SOURCE_LARGE) Grouping.NONE else Grouping.AUTO,
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding(),
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + if (isTrash) 96.dp else 16.dp
                    ),
                )
            }
            if (isTrash && !list.isNullOrEmpty()) {
                val targets = if (selection.isEmpty()) list else list.filter { it.id in selection }
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(WindowInsets.navigationBars.asPaddingValues())
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        onClick = { scope.launch { if (actions.restore(targets)) selection = emptySet() } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.RestoreFromTrash, null)
                        Text(if (selection.isEmpty()) " Alle wiederherstellen" else " Wiederherstellen")
                    }
                    Button(
                        onClick = { scope.launch { if (actions.deleteForever(targets)) selection = emptySet() } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.DeleteForever, null)
                        Text(if (selection.isEmpty()) " Leeren" else " Endgültig löschen")
                    }
                }
            }
        }
    }
}
