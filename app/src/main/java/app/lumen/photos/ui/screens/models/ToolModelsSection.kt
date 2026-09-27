package app.lumen.photos.ui.screens.models

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.container
import app.lumen.photos.edit.ToolKind
import app.lumen.photos.edit.ToolModel
import app.lumen.photos.edit.ToolModelCatalog
import app.lumen.photos.ui.components.Dots
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.ShapeIcon
import kotlinx.coroutines.launch

/** Choose / download the models of one image tool (upscaling, object removal, background). */
@Composable
fun ToolModelsSection(kind: ToolKind, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val active by c.tools.active(kind).collectAsStateWithLifecycle()
    val installed by c.models.installed.collectAsStateWithLifecycle()
    val downloads by c.ai.downloads.collectAsStateWithLifecycle(initialValue = emptyMap())
    val failed by c.ai.failedDownloads.collectAsStateWithLifecycle(initialValue = emptyMap())
    var delete by remember { mutableStateOf<ToolModel?>(null) }
    val icon = when (kind) {
        ToolKind.UPSCALE -> Icons.Outlined.Hd
        ToolKind.INPAINT -> Icons.Outlined.AutoFixHigh
        ToolKind.SEGMENT -> Icons.Outlined.PersonOutline
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(kind.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        ToolModelCatalog.of(kind).forEach { model ->
            val isInstalled = model.id in installed
            val isActive = active == model && isInstalled
            val progress = downloads[model.id]
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = if (isActive) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(if (isActive) 2.dp else 1.dp, if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().animateContentSize()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShapeIcon(
                            icon,
                            shape = when (model.tier) {
                                "Schnell" -> MaterialShapes.Pill
                                "Sehr gut" -> MaterialShapes.Sunny
                                else -> MaterialShapes.Cookie6Sided
                            }.toShape(),
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                            size = 48.dp
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(model.tier, style = MaterialTheme.typography.titleLarge)
                            Text(model.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isActive) Icon(Icons.Outlined.Check, "Aktiv", tint = MaterialTheme.colorScheme.secondary)
                    }
                    Text(model.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tempo", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
                        Dots(model.speed)
                    }
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Qualität", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
                        Dots(model.accuracy, color = MaterialTheme.colorScheme.secondary)
                    }
                    Text(
                        "${Format.bytes(model.totalBytes)} · " + when (kind) {
                            ToolKind.UPSCALE -> "ca. ${Format.etaSeconds(model.seconds.toLong())} pro Megapixel"
                            else -> "ca. ${Format.etaSeconds(model.seconds.toLong().coerceAtLeast(1))} pro Bild"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    failed[model.id]?.takeIf { progress == null && !isInstalled }?.let {
                        Text("Download fehlgeschlagen: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    AnimatedVisibility(progress != null) {
                        LinearWavyProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        when {
                            progress != null -> OutlinedButton(onClick = { c.ai.cancelDownload(model) }) { Text("Abbrechen") }
                            !isInstalled -> Button(onClick = { c.ai.download(model) }, shapes = ButtonDefaults.shapes()) {
                                Icon(Icons.Outlined.CloudDownload, null); Text(" Herunterladen")
                            }
                            !isActive -> Button(onClick = { scope.launch { c.tools.setActive(kind, model) } }, shapes = ButtonDefaults.shapes()) {
                                Text("Verwenden")
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (isInstalled) IconButton(onClick = { delete = model }) { Icon(Icons.Outlined.Delete, "Löschen") }
                    }
                }
            }
        }
    }

    delete?.let { model ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("${model.name} löschen?") },
            text = { Text("Das Modell (${Format.bytes(c.models.diskUsage(model))}) wird vom Gerät entfernt. Du kannst es jederzeit neu laden.") },
            confirmButton = {
                Button(onClick = { delete = null; scope.launch { c.tools.delete(model) } }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Abbrechen") } }
        )
    }
}
