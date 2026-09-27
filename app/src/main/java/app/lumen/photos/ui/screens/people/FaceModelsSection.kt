package app.lumen.photos.ui.screens.people

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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Face
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
import app.lumen.photos.face.FaceModel
import app.lumen.photos.face.FaceModelCatalog
import app.lumen.photos.ui.components.Dots
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.ShapeIcon
import kotlinx.coroutines.launch

/** Choose / download the face recognition quality: Schnell, Ausgewogen, Sehr gut. */
@Composable
fun FaceModelsSection(modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val active by c.faces.activeModel.collectAsStateWithLifecycle()
    val installed by c.models.installed.collectAsStateWithLifecycle()
    val downloads by c.ai.downloads.collectAsStateWithLifecycle(initialValue = emptyMap())
    val failed by c.ai.failedDownloads.collectAsStateWithLifecycle(initialValue = emptyMap())
    val media by c.media.media.collectAsStateWithLifecycle()
    val photos = remember(media) { media.count { it.isImage } }
    var switchTo by remember { mutableStateOf<FaceModel?>(null) }
    var delete by remember { mutableStateOf<FaceModel?>(null) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FaceModelCatalog.models.forEach { model ->
            val isActive = active == model
            val isInstalled = model.id in installed
            val progress = downloads[model.id]
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = if (isActive) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(if (isActive) 2.dp else 1.dp, if (isActive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().animateContentSize()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShapeIcon(
                            Icons.Outlined.Face,
                            shape = when (model.tier) {
                                "Schnell" -> MaterialShapes.Pill
                                "Ausgewogen" -> MaterialShapes.Cookie6Sided
                                else -> MaterialShapes.Sunny
                            }.toShape(),
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer,
                            size = 48.dp
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(model.tier, style = MaterialTheme.typography.titleLarge)
                            Text(model.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isActive) Icon(Icons.Outlined.Check, "Aktiv", tint = MaterialTheme.colorScheme.tertiary)
                    }
                    Text(model.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tempo", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
                        Dots(model.speed)
                    }
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Genauigkeit", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
                        Dots(model.accuracy, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Text(
                        "${Format.bytes(model.totalBytes)} · ca. ${Format.etaSeconds(photos.toLong() * model.msPerImage / 1000)} für ${Format.count(photos)} Fotos",
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
                            !isInstalled -> Button(
                                onClick = {
                                    if (active == null) scope.launch { c.faces.setActiveModel(model) }
                                    c.ai.download(model)
                                },
                                shapes = ButtonDefaults.shapes()
                            ) { Icon(Icons.Outlined.CloudDownload, null); Text(" Herunterladen") }
                            !isActive -> Button(
                                onClick = { if (active == null) scope.launch { c.faces.setActiveModel(model); c.faces.schedule() } else switchTo = model },
                                shapes = ButtonDefaults.shapes()
                            ) { Text("Verwenden") }
                        }
                        Spacer(Modifier.weight(1f))
                        if (isInstalled) IconButton(onClick = { delete = model }) { Icon(Icons.Outlined.Delete, "Löschen") }
                    }
                }
            }
        }
    }

    switchTo?.let { model ->
        AlertDialog(
            onDismissRequest = { switchTo = null },
            title = { Text("Zu „${model.tier}“ wechseln?") },
            text = { Text("Jedes Modell erzeugt eigene Gesichtsabdrücke. Alle Fotos werden neu gescannt, Namen musst du danach neu vergeben – die bisherigen bleiben beim alten Modell gespeichert.") },
            confirmButton = {
                Button(onClick = {
                    switchTo = null
                    scope.launch { c.faces.setActiveModel(model); c.faces.schedule(replace = true) }
                }) { Text("Wechseln") }
            },
            dismissButton = { TextButton(onClick = { switchTo = null }) { Text("Abbrechen") } }
        )
    }
    delete?.let { model ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("${model.tier} löschen?") },
            text = { Text("Modell, erkannte Gesichter und Namen dieses Modells werden entfernt.") },
            confirmButton = {
                Button(onClick = {
                    delete = null
                    scope.launch {
                        c.faces.resetModel(model)
                        c.models.delete(model)
                        if (active == model) c.settings.update { it.copy(activeFaceModelId = null) }
                    }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Abbrechen") } }
        )
    }
}
