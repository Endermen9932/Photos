package app.lumen.photos.ui.screens.albums

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.MediaListRegistry
import app.lumen.photos.container
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.MediaThumbnail
import app.lumen.photos.ui.navigation.LocalNavigator

@Composable
fun AlbumsTab() {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val albums by c.media.albums.collectAsStateWithLifecycle()
    val media by c.media.media.collectAsStateWithLifecycle()
    val counts = remember(media) {
        Triple(media.count { it.isFavorite }, media.count { it.isVideo }, media.count { it.isScreenshot })
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 12.dp, bottom = bottom + 100.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Alben", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(vertical = 8.dp))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CollectionChip(
                        Icons.Outlined.FavoriteBorder, "Favoriten", counts.first, MaterialShapes.Heart.toShape(),
                        MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
                        Modifier.weight(1f)
                    ) { nav.collection(MediaListRegistry.SOURCE_FAVORITES, "Favoriten") }
                    CollectionChip(
                        Icons.Outlined.Videocam, "Videos", counts.second, MaterialShapes.Pill.toShape(),
                        MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                        Modifier.weight(1f)
                    ) { nav.collection(MediaListRegistry.SOURCE_VIDEOS, "Videos") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CollectionChip(
                        Icons.Outlined.Screenshot, "Screenshots", counts.third, MaterialShapes.Slanted.toShape(),
                        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                        Modifier.weight(1f)
                    ) { nav.collection(MediaListRegistry.SOURCE_SCREENSHOTS, "Screenshots") }
                    CollectionChip(
                        Icons.Outlined.Delete, "Papierkorb", null, MaterialShapes.Cookie4Sided.toShape(),
                        MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface,
                        Modifier.weight(1f)
                    ) { nav.collection(MediaListRegistry.SOURCE_TRASH, "Papierkorb") }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Auf dem Gerät", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        }
        items(albums, key = { it.id }) { album ->
            Column(
                Modifier
                    .animateItem()
                    .clip(RoundedCornerShape(24.dp))
            ) {
                Surface(
                    onClick = { nav.collection(MediaListRegistry.album(album.id), album.name) },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                ) {
                    MediaThumbnail(album.cover, shared = false, showBadges = false, size = 512, modifier = Modifier.fillMaxSize())
                }
                Text(
                    album.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
                Text(
                    "${Format.count(album.count)} · ${Format.bytes(album.totalSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CollectionChip(
    icon: ImageVector,
    title: String,
    count: Int?,
    shape: Shape,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = container, contentColor = content, modifier = modifier) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(shape)
                    .padding(0.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(shape = shape, color = content.copy(alpha = 0.12f), modifier = Modifier.height(40.dp).aspectRatio(1f)) {}
                Icon(icon, null)
            }
            Spacer(Modifier.padding(start = 12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (count != null) Text(Format.count(count), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
