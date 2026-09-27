package app.lumen.photos.ui.screens.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import app.lumen.photos.container
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.edit.ToolKind
import app.lumen.photos.edit.ToolModelCatalog
import app.lumen.photos.ui.components.ConnectedToggleGroup
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.ShapeIcon
import app.lumen.photos.ui.navigation.LocalNavigator
import kotlinx.coroutines.launch

/** "Hochskalieren" from the viewer: pick model and factor, then it runs in the background. */
@Composable
fun UpscaleSheet(item: MediaItem, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val active by c.tools.active(ToolKind.UPSCALE).collectAsStateWithLifecycle()
    val installed by c.models.installed.collectAsStateWithLifecycle()
    val jobs by c.tools.upscales.collectAsStateWithLifecycle(initialValue = emptyMap())
    val job = jobs[item.id]
    var factor by remember { mutableIntStateOf(if (item.megapixels > 4f) 2 else 4) }
    val models = ToolModelCatalog.of(ToolKind.UPSCALE)
    val available = models.filter { it.id in installed }
    val model = active?.takeIf { it.id in installed } ?: available.lastOrNull()
    val plan = remember(item, factor) { c.tools.plan(item, factor) }
    val running = job != null && !job.state.isFinished

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShapeIcon(Icons.Outlined.Hd, shape = MaterialShapes.Cookie9Sided.toShape(), size = 52.dp, spin = running)
                Column {
                    Text("Hochskalieren", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Jetzt ${item.displayWidth} × ${item.displayHeight} · ${"%.1f".format(item.megapixels)} MP",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (available.isEmpty()) {
                Text(
                    "Für das Hochskalieren wird ein KI-Modell benötigt (ab 5 MB). Es läuft danach komplett offline.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = { onDismiss(); nav.models() }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Outlined.CloudDownload, null); Text(" Modell auswählen")
                }
                return@Column
            }

            Text("Modell", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                models.forEach { m ->
                    val ok = m.id in installed
                    FilterChip(
                        selected = m == model,
                        enabled = ok && !running,
                        onClick = { scope.launch { c.tools.setActive(ToolKind.UPSCALE, m) } },
                        label = { Text(m.tier) },
                        leadingIcon = if (m == model) { { Icon(Icons.Outlined.Check, null) } } else null,
                    )
                }
            }
            if (available.size < models.size) {
                Text(
                    "Weitere Modelle unter Einstellungen → KI-Modelle",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("Vergrößerung", style = MaterialTheme.typography.titleSmall)
            ConnectedToggleGroup(
                options = listOf(2, 4),
                selected = factor,
                onSelect = { if (!running) factor = it },
                label = { "$it×" },
                modifier = Modifier.fillMaxWidth()
            )
            if (model != null) {
                Text(
                    "Ergebnis: ${plan.outputWidth} × ${plan.outputHeight} · ${"%.1f".format(plan.outputWidth.toLong() * plan.outputHeight / 1_000_000f)} MP · " +
                        "ca. ${Format.etaSeconds(c.tools.estimateSeconds(model, plan))}",
                    style = MaterialTheme.typography.bodyLarge
                )
                if (plan.limited) {
                    Text(
                        "Das Ergebnis wird auf 40 MP begrenzt – für große Fotos eignet sich 2× besser.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Text(
                "Das Original bleibt erhalten, das Ergebnis wird als Kopie daneben gespeichert. Die Berechnung läuft im Hintergrund weiter, auch wenn du die App verlässt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                running -> {
                    val p = job!!
                    Text(
                        if (p.total > 0) "${p.done * 100 / p.total} %" + (if (p.secondsLeft >= 0) " · noch ca. ${Format.etaSeconds(p.secondsLeft)}" else "")
                        else if (p.state == WorkInfo.State.RUNNING) "Wird vorbereitet …" else "Wartet …",
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (p.total > 0) LinearWavyProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                    else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { c.tools.cancelUpscale() }) { Text("Abbrechen") }
                }
                else -> {
                    when (job?.state) {
                        WorkInfo.State.SUCCEEDED -> Text("Fertig: ${job.resultName} liegt neben dem Original.", color = MaterialTheme.colorScheme.primary)
                        WorkInfo.State.FAILED -> Text("Fehlgeschlagen: ${job.error}", color = MaterialTheme.colorScheme.error)
                        else -> Unit
                    }
                    Button(
                        onClick = { model?.let { c.tools.startUpscale(item, it, factor) } },
                        enabled = model != null,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Icon(Icons.Outlined.Hd, null); Text(" Hochskalieren") }
                }
            }
        }
    }
}
