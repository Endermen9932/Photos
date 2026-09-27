package app.lumen.photos.ui.screens.optimize

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.HdrOn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import app.lumen.photos.container
import app.lumen.photos.data.settings.OptimizeMode
import app.lumen.photos.data.settings.OptimizerSettings
import app.lumen.photos.data.settings.OutputFormat
import app.lumen.photos.data.settings.TargetResolution
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
import kotlinx.coroutines.launch

private data class QualityPreset(val label: String, val quality: Int)

private val qualityPresets = listOf(
    QualityPreset("Max", 95),
    QualityPreset("Hoch", 90),
    QualityPreset("Gut", 82),
    QualityPreset("Kompakt", 72),
    QualityPreset("Klein", 60),
)

@Composable
fun OptimizeScreen() {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val vm: OptimizeViewModel = viewModel { OptimizeViewModel(context.container) }
    val actions = rememberMediaActions()
    val scope = rememberCoroutineScope()

    val s by vm.settings.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val previewItem by vm.previewItem.collectAsStateWithLifecycle()
    val previewLoading by vm.previewLoading.collectAsStateWithLifecycle()
    val previewError by vm.previewError.collectAsStateWithLifecycle()
    val estimate by vm.estimate.collectAsStateWithLifecycle()
    val estimating by vm.estimating.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var permissionTick by remember { mutableIntStateOf(0) }

    val hasLocation = remember(permissionTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val canManage = remember(permissionTick) { context.container.media.canManageMedia() }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }
    val manageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionTick++ }

    val running = progress?.state == WorkInfo.State.RUNNING || progress?.state == WorkInfo.State.ENQUEUED

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speicher optimieren") },
                navigationIcon = { BackButton { nav.back() } },
            )
        },
        bottomBar = {
            if (!running && progress?.state != WorkInfo.State.SUCCEEDED) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                    Button(
                        onClick = { confirm = true },
                        enabled = candidates.isNotEmpty(),
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp)
                            .height(64.dp)
                    ) {
                        Icon(Icons.Outlined.Compress, null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (candidates.isEmpty()) "Nichts zu optimieren"
                            else "${Format.count(candidates.size)} Fotos optimieren",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    ) { padding ->
        val p = progress
        if (p != null && (running || p.state == WorkInfo.State.SUCCEEDED)) {
            ProgressView(p, onCancel = { vm.cancel() }, onDone = { vm.dismissResult() }, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            SummaryCard(candidates.size, candidates.sumOf { it.size }, estimate, estimating, s)

            SectionTitle("Vorschau")
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .aspectRatio(
                        preview?.let { it.width.toFloat() / it.height }?.coerceIn(0.6f, 1.6f) ?: 0.75f
                    )
                    .animateContentSize()
            ) {
                val pr = preview
                if (pr != null) {
                    BeforeAfter(
                        before = remember(pr) { pr.original.asImageBitmap() },
                        after = remember(pr) { pr.compressed.asImageBitmap() },
                        beforeLabel = "Original · ${Format.bytes(pr.originalBytes)}",
                        afterLabel = "Neu · ${Format.bytes(pr.compressedBytes)}",
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (previewError != null) {
                    Text("Vorschau nicht möglich: $previewError", modifier = Modifier.align(Alignment.Center).padding(24.dp))
                } else if (candidates.isEmpty()) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        ShapeIcon(Icons.Outlined.CheckCircle, size = 96.dp, shape = MaterialShapes.Sunny.toShape())
                        Spacer(Modifier.height(12.dp))
                        Text("Alles bereits optimal", style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (previewLoading) {
                    ContainedLoadingIndicator(Modifier.align(Alignment.Center).size(64.dp))
                }
            }
            preview?.let { pr ->
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val saved = 1f - pr.compressedBytes.toFloat() / pr.originalBytes.coerceAtLeast(1)
                    Chip("${pr.item.width}×${pr.item.height} → ${pr.width}×${pr.height}")
                    Chip("−${Format.percent(saved.coerceAtLeast(0f))}", highlight = true)
                    if (pr.hasGainmap) Chip("Ultra HDR", icon = true)
                }
            }
            if (candidates.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    items(candidates.take(40), key = { it.id }) { item ->
                        val sel = item.id == previewItem?.id
                        Surface(
                            onClick = { vm.selectPreview(item) },
                            shape = RoundedCornerShape(if (sel) 22.dp else 14.dp),
                            border = if (sel) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.size(64.dp)
                        ) {
                            MediaThumbnail(item, shared = false, showBadges = false, size = 192, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }

            SectionTitle("Zielauflösung")
            ConnectedToggleGroup(
                options = TargetResolution.entries,
                selected = s.resolution,
                onSelect = { r -> vm.update { it.copy(resolution = r) } },
                label = { it.label.substringBefore(" ·").replace("Originalauflösung", "Original") },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                s.resolution.label + if (s.resolution != TargetResolution.ORIGINAL) " – längere Kante max. ${s.resolution.longEdge} px" else " – nur neu komprimieren",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )

            SectionTitle("Qualität", trailing = {
                Text("${s.quality} %", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            })
            ConnectedToggleGroup(
                options = qualityPresets,
                selected = qualityPresets.firstOrNull { it.quality == s.quality } ?: QualityPreset("", -1),
                onSelect = { q -> vm.update { it.copy(quality = q.quality) } },
                label = { it.label },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            var sliderValue by remember(s.quality) { mutableStateOf(s.quality.toFloat()) }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { vm.update { it.copy(quality = sliderValue.toInt()) } },
                valueRange = 40f..100f,
                steps = 59,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Text(
                qualityHint(s.quality),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            SectionTitle("Vorgehen")
            ConnectedToggleGroup(
                options = OptimizeMode.entries,
                selected = s.mode,
                onSelect = { m -> vm.update { it.copy(mode = m) } },
                label = { if (it == OptimizeMode.REPLACE) "Ersetzen" else "Kopie + Papierkorb" },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                if (s.mode == OptimizeMode.REPLACE) "Die Originaldatei wird überschrieben. Datum, Ort und Kameradaten bleiben erhalten, das Foto bleibt an seiner Stelle in der Timeline."
                else "Es wird eine optimierte Kopie angelegt. Das Original landet im Papierkorb und kann 30 Tage lang wiederhergestellt werden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            AnimatedVisibility(s.mode == OptimizeMode.COPY_AND_TRASH) {
                Column {
                    SectionTitle("Format")
                    ConnectedToggleGroup(
                        options = OutputFormat.entries,
                        selected = s.format,
                        onSelect = { f -> vm.update { it.copy(format = f) } },
                        label = { it.label },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            SectionTitle("Optionen")
            ToggleRow("Ultra HDR erhalten", "Die HDR-Helligkeitsinformationen von Pixel-Fotos werden mitskaliert (nur JPEG).", s.keepUltraHdr) { v -> vm.update { it.copy(keepUltraHdr = v) } }
            ToggleRow("Metadaten erhalten", "Aufnahmedatum, Kamera, Objektiv und GPS-Position übernehmen.", s.keepMetadata) { v -> vm.update { it.copy(keepMetadata = v) } }
            ToggleRow("Favoriten auslassen", "Als Favorit markierte Fotos bleiben unverändert.", s.skipFavorites) { v -> vm.update { it.copy(skipFavorites = v) } }
            ToggleRow("PNG, HEIC & WebP einbeziehen", "Werden als ${s.format.label}-Kopie gespeichert, das Original kommt in den Papierkorb.", s.includeOtherFormats) { v -> vm.update { it.copy(includeOtherFormats = v) } }
            ToggleRow("Auch kleinere Fotos neu komprimieren", "Fotos, die schon in die Zielauflösung passen, trotzdem neu kodieren.", s.recompressSmaller) { v -> vm.update { it.copy(recompressSmaller = v) } }
            ListItem(
                headlineContent = { Text("Mindestersparnis: ${s.minSavingsPercent} %") },
                supportingContent = {
                    var v by remember(s.minSavingsPercent) { mutableStateOf(s.minSavingsPercent.toFloat()) }
                    Slider(
                        value = v,
                        onValueChange = { v = it },
                        onValueChangeFinished = { vm.update { it.copy(minSavingsPercent = v.toInt()) } },
                        valueRange = 0f..50f,
                        steps = 9
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
            )

            if (!hasLocation) {
                Banner(
                    Icons.Outlined.LocationOn,
                    "Ortsdaten-Zugriff fehlt",
                    "Ohne diese Berechtigung blendet Android GPS-Daten aus – sie würden beim Optimieren verloren gehen.",
                    "Erlauben"
                ) { locationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }
            }
            if (!canManage) {
                Banner(
                    Icons.Outlined.Info,
                    "Tipp: Medienverwaltung erlauben",
                    "Dann fragt Android nicht bei jedem Stapel nach Bestätigung, und Hintergrund-Optimierungen laufen reibungslos.",
                    "Einstellungen"
                ) { manageLauncher.launch(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).setData(android.net.Uri.parse("package:${context.packageName}"))) }
            }
        }
    }

    if (confirm) {
        val est = estimate
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Outlined.Warning, null) },
            title = { Text("${Format.count(candidates.size)} Fotos optimieren?") },
            text = {
                Text(
                    buildString {
                        append("Auflösung: ${s.resolution.label}\nQualität: ${s.quality} %\n")
                        if (est != null) append("Voraussichtlich frei: ${Format.bytes(est.savedBytes)}\n\n")
                        append(
                            if (s.mode == OptimizeMode.REPLACE) "Die Originale werden überschrieben. Das lässt sich nicht rückgängig machen – erstelle vorher ein Backup (Werkzeuge → Backup), wenn du die vollen Originale behalten willst."
                            else "Die Originale landen im Papierkorb und können 30 Tage lang wiederhergestellt werden."
                        )
                    }
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    scope.launch {
                        val targets = candidates
                        if (actions.requestWrite(targets)) vm.start(targets)
                    }
                }) { Text("Optimieren") }
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

private fun qualityHint(q: Int) = when {
    q >= 93 -> "Praktisch verlustfrei – kaum Ersparnis durch die Kompression selbst."
    q >= 86 -> "Sehr hohe Qualität, Unterschiede sind auch vergrößert kaum sichtbar."
    q >= 78 -> "Empfohlen: kaum sichtbarer Unterschied bei deutlich kleineren Dateien."
    q >= 68 -> "Kompakt: bei starkem Zoom leichte Artefakte in feinen Strukturen."
    else -> "Maximale Ersparnis, Artefakte in Himmel und Details können sichtbar werden."
}

@Composable
private fun SummaryCard(count: Int, total: Long, estimate: app.lumen.photos.optimize.Estimate?, estimating: Boolean, s: OptimizerSettings) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        Column(Modifier.padding(22.dp)) {
            Text("Voraussichtlich frei", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedNumber(estimate?.savedBytes ?: 0, { Format.bytes(it) }, EmphasizedNumber, Modifier.weight(1f))
                if (estimating) LoadingIndicator(Modifier.size(40.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${Format.count(count)} Fotos · ${Format.bytes(total)} → " +
                    (estimate?.let { Format.bytes(it.projectedBytes) } ?: "…"),
                style = MaterialTheme.typography.bodyLarge
            )
            estimate?.let {
                Text(
                    "Hochgerechnet aus ${it.sampled} Stichproben mit ${s.quality} % Qualität · −${Format.percent(it.savedFraction)}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, highlight: Boolean = false, icon: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (highlight) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (highlight) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon) {
                Icon(Icons.Outlined.HdrOn, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clip(RoundedCornerShape(20.dp)),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, action: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun ProgressView(p: OptimizeProgress, onCancel: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val finished = p.state == WorkInfo.State.SUCCEEDED
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
            AnimatedContent(finished, label = "progress") { f ->
                if (f) {
                    ShapeIcon(Icons.Outlined.CheckCircle, size = 200.dp, shape = MaterialShapes.Cookie12Sided.toShape(), spin = true)
                } else {
                    CircularWavyProgressIndicator(
                        progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                        modifier = Modifier.size(220.dp)
                    )
                }
            }
            if (!finished) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${Format.count(p.done)}", style = EmphasizedNumber)
                    Text("von ${Format.count(p.total)}", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(if (finished) "Fertig!" else "Optimiere Fotos …", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        AnimatedNumber(p.saved, { "${Format.bytes(it)} freigegeben" }, MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        if (p.skipped > 0 || p.failed > 0) {
            Text(
                listOfNotNull(
                    if (p.skipped > 0) "${p.skipped} übersprungen (zu wenig Ersparnis)" else null,
                    if (p.failed > 0) "${p.failed} fehlgeschlagen" else null
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        if (!finished) {
            Text(
                "Läuft auch im Hintergrund weiter – du kannst die App verlassen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))
        if (finished) Button(onClick = onDone, shapes = ButtonDefaults.shapes()) { Text("Fertig") }
        else OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
    }
}
