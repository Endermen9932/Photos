package app.lumen.photos.ui.screens.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.MediaListRegistry
import app.lumen.photos.container
import app.lumen.photos.data.db.OptimizedTotals
import app.lumen.photos.optimize.ImageOptimizer
import app.lumen.photos.ui.components.ActionCard
import app.lumen.photos.ui.components.AnimatedNumber
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.navigation.LocalNavigator
import app.lumen.photos.ui.theme.EmphasizedNumber

@Composable
fun ToolsTab() {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val media by c.media.media.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val model by c.ai.activeModel.collectAsStateWithLifecycle()
    val indexed by c.ai.indexedCount.collectAsStateWithLifecycle()
    val totals by c.db.optimized().totals().collectAsStateWithLifecycle(initialValue = OptimizedTotals(0, 0))
    val done by produceState(emptySet<Long>(), totals) { value = c.optimizer.optimizedIds() }

    val photoBytes = remember(media) { media.filter { it.isImage }.sumOf { it.size } }
    val videoBytes = remember(media) { media.filter { it.isVideo }.sumOf { it.size } }
    val candidates = remember(media, settings.optimizer, done) {
        media.filter { ImageOptimizer.isCandidate(it, settings.optimizer, done) }
    }
    val potential = remember(candidates, settings.optimizer) {
        candidates.sumOf { (it.size * (1f - ImageOptimizer.heuristicRatio(it, settings.optimizer))).toLong() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Werkzeuge", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp))

        // Storage hero card.
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Galerie-Speicher", style = MaterialTheme.typography.titleMedium)
                        AnimatedNumber(photoBytes + videoBytes, { Format.bytes(it) }, EmphasizedNumber)
                        Text(
                            "Fotos ${Format.bytes(photoBytes)} · Videos ${Format.bytes(videoBytes)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    StorageRing(
                        photos = photoBytes.toFloat(),
                        videos = videoBytes.toFloat(),
                        savable = potential.toFloat(),
                        modifier = Modifier.size(96.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (candidates.isNotEmpty()) {
                    Text(
                        "${Format.count(candidates.size)} Fotos sind größer als ${settings.optimizer.resolution.label.substringBefore(" ·")}. " +
                            "Geschätzt ca. ${Format.bytes(potential)} frei machbar.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    Text("Alle Fotos sind bereits optimiert. 🎉", style = MaterialTheme.typography.bodyLarge)
                }
                if ((totals.saved ?: 0) > 0) {
                    Text(
                        "Bisher gespart: ${Format.bytes(totals.saved ?: 0)} in ${Format.count(totals.count)} Fotos",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { nav.optimize() },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(Icons.Outlined.Compress, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Speicher optimieren")
                }
            }
        }

        ActionCard(
            Icons.Outlined.AutoAwesome,
            "KI-Modelle & Index",
            model?.let { "${it.tier} · ${it.name} · ${Format.count(indexed)} indexiert" } ?: "Offline-KI-Suche einrichten",
            onClick = { nav.models() },
            shape = MaterialShapes.SoftBurst.toShape(),
            iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
            iconContent = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        ActionCard(
            Icons.Outlined.Backup,
            "Backup",
            settings.backup.folderName?.let { f ->
                if (settings.backup.lastBackupAt > 0) "$f · zuletzt ${Format.short(settings.backup.lastBackupAt)}" else "Ziel: $f"
            } ?: "Galerie auf USB-Stick, SD-Karte oder NAS sichern",
            onClick = { nav.backup() },
            shape = MaterialShapes.Cookie9Sided.toShape(),
            iconContainer = MaterialTheme.colorScheme.primaryContainer,
            iconContent = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        ActionCard(
            Icons.Outlined.ContentCopy,
            "Duplikate & Serien",
            "Doppelte Dateien und fast gleiche Fotos finden",
            onClick = { nav.duplicates() },
            shape = MaterialShapes.Clover4Leaf.toShape(),
        )
        ActionCard(
            Icons.Outlined.Storage,
            "Größte Dateien",
            "Die Speicherfresser deiner Galerie",
            onClick = { nav.collection(MediaListRegistry.SOURCE_LARGE, "Größte Dateien") },
            shape = MaterialShapes.Gem.toShape(),
        )
        ActionCard(
            Icons.Outlined.Delete,
            "Papierkorb",
            "Gelöschte Elemente wiederherstellen",
            onClick = { nav.collection(MediaListRegistry.SOURCE_TRASH, "Papierkorb") },
            shape = MaterialShapes.Cookie4Sided.toShape(),
        )
        ActionCard(
            Icons.Outlined.Settings,
            "Einstellungen",
            "Design, Galerie, KI und Speicher",
            onClick = { nav.settings() },
            shape = MaterialShapes.Cookie12Sided.toShape(),
        )
    }
}

@Composable
private fun StorageRing(photos: Float, videos: Float, savable: Float, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
    val saveColor = MaterialTheme.colorScheme.secondary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val total = (photos + videos).coerceAtLeast(1f)
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            val photoSweep = 360f * photos / total
            val gap = 6f
            drawArc(primary, -90f, (photoSweep - gap).coerceAtLeast(0f), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(tertiary, -90f + photoSweep, (360f - photoSweep - gap).coerceAtLeast(0f), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val saveSweep = 360f * (savable / total).coerceIn(0f, 1f)
            if (saveSweep > 1f) {
                val inner = stroke * 1.6f
                drawArc(
                    saveColor, -90f, saveSweep, false,
                    Offset(inner, inner), Size(size.width - inner * 2, size.height - inner * 2),
                    style = Stroke(stroke / 2, cap = StrokeCap.Round)
                )
            }
        }
    }
}
