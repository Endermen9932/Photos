package app.lumen.photos.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.photos.container
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.ui.rememberMediaActions
import kotlinx.coroutines.launch

/** Top bar shown while items are selected, with the common bulk actions. */
@Composable
fun SelectionBar(
    selected: List<MediaItem>,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
    extraActions: @Composable () -> Unit = {},
) {
    val actions = rememberMediaActions()
    val scope = rememberCoroutineScope()
    var moving by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp)
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Auswahl aufheben") }
            Column(Modifier.weight(1f)) {
                Text("${selected.size} ausgewählt", style = MaterialTheme.typography.titleMedium)
                Text(Format.bytes(selected.sumOf { it.size }), style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, "Alle auswählen") }
            IconButton(onClick = { actions.share(selected) }) { Icon(Icons.Outlined.Share, "Teilen") }
            IconButton(onClick = {
                scope.launch { actions.setFavorite(selected, !selected.all { it.isFavorite }) }
            }) { Icon(Icons.Outlined.FavoriteBorder, "Favorit") }
            IconButton(onClick = { moving = true }) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, "Verschieben") }
            extraActions()
            FilledTonalIconButton(onClick = {
                scope.launch { if (actions.trash(selected)) onClear() }
            }) { Icon(Icons.Outlined.Delete, "In den Papierkorb") }
        }
    }

    if (moving) {
        MoveDialog(
            onDismiss = { moving = false },
            onMove = { path ->
                moving = false
                scope.launch {
                    val n = actions.move(selected, path)
                    Toast.makeText(context, "$n Elemente verschoben", Toast.LENGTH_SHORT).show()
                    onClear()
                }
            }
        )
    }
}

@Composable
fun MoveDialog(onDismiss: () -> Unit, onMove: (String) -> Unit) {
    val albums by LocalContext.current.container.media.albums.collectAsState()
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("In Album verschieben") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.replace("/", "") },
                    label = { Text("Neues Album") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(albums, key = { it.id }) { album ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onMove(album.relativePath) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MediaThumbnail(
                                album.cover, shared = false, showBadges = false, size = 128,
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                            )
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(album.relativePath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = { onMove("Pictures/${newName.trim()}") }) { Text("Neues Album") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
