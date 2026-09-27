package app.lumen.photos.ui.screens.backup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.lumen.photos.container
import app.lumen.photos.ui.components.BackButton
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.SectionTitle
import app.lumen.photos.ui.components.ShapeIcon
import app.lumen.photos.ui.navigation.LocalNavigator
import app.lumen.photos.work.BackupWorker
import kotlinx.coroutines.launch

@Composable
fun BackupScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val backup = settings.backup
    val media by c.media.media.collectAsStateWithLifecycle()
    val wm = remember { WorkManager.getInstance(context) }
    val infos by wm.getWorkInfosForUniqueWorkFlow(BackupWorker.NAME).collectAsStateWithLifecycle(initialValue = emptyList())
    val info = infos.firstOrNull()
    val running = info != null && !info.state.isFinished
    val totalBytes = remember(media, backup.includeVideos) { media.filter { backup.includeVideos || it.isImage }.sumOf { it.size } }
    val totalCount = remember(media, backup.includeVideos) { media.count { backup.includeVideos || it.isImage } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val name = DocumentFile.fromTreeUri(context, uri)?.name
            scope.launch { c.settings.updateBackup { it.copy(treeUri = uri.toString(), folderName = name) } }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Backup") }, navigationIcon = { BackButton { nav.back() } }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Column(Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShapeIcon(
                            Icons.Outlined.Backup,
                            shape = MaterialShapes.Cookie9Sided.toShape(),
                            container = MaterialTheme.colorScheme.secondary,
                            content = MaterialTheme.colorScheme.onSecondary,
                            size = 56.dp,
                            spin = running
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Galerie sichern", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${Format.count(totalCount)} Dateien · ${Format.bytes(totalBytes)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Kopiert alle Fotos und Videos mit ihrer Ordnerstruktur in einen Ordner deiner Wahl – USB-Stick, SD-Karte " +
                            "oder Cloud-/NAS-Anbieter (z. B. Nextcloud) über den Android-Dateimanager. Nur neue und geänderte Dateien " +
                            "werden kopiert. Ideal vor dem Speicher-Optimieren.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (backup.lastBackupAt > 0) {
                        Text(
                            "Letztes Backup: ${Format.full(backup.lastBackupAt)} · ${backup.lastBackupCopied} neue Dateien",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }

            SectionTitle("Ziel")
            ListItem(
                leadingContent = { Icon(Icons.Outlined.FolderOpen, null) },
                headlineContent = { Text(backup.folderName ?: "Kein Ordner gewählt") },
                supportingContent = { Text(if (backup.treeUri != null) "Backups landen im Unterordner „${BackupWorker.ROOT_NAME}“" else "Tippe auf „Wählen“") },
                trailingContent = { FilledTonalButton(onClick = { picker.launch(null) }) { Text(if (backup.treeUri == null) "Wählen" else "Ändern") } }
            )

            SectionTitle("Optionen")
            ListItem(
                headlineContent = { Text("Videos sichern") },
                trailingContent = {
                    Switch(backup.includeVideos, onCheckedChange = { v -> scope.launch { c.settings.updateBackup { it.copy(includeVideos = v) } } })
                }
            )
            ListItem(
                headlineContent = { Text("Automatisch jede Nacht") },
                supportingContent = { Text("Einmal täglich beim Laden, wenn das Ziel erreichbar ist") },
                trailingContent = {
                    Switch(backup.autoBackup, enabled = backup.treeUri != null, onCheckedChange = { v ->
                        scope.launch {
                            c.settings.updateBackup { it.copy(autoBackup = v) }
                            BackupWorker.schedulePeriodic(context, v)
                        }
                    })
                }
            )

            AnimatedVisibility(running || info?.state == WorkInfo.State.SUCCEEDED || info?.state == WorkInfo.State.FAILED) {
                val data = if (info?.state?.isFinished == true) info.outputData else info?.progress
                val done = data?.getInt(BackupWorker.KEY_DONE, 0) ?: 0
                val total = data?.getInt(BackupWorker.KEY_TOTAL, 0) ?: 0
                val copied = data?.getInt(BackupWorker.KEY_COPIED, 0) ?: 0
                val restore = data?.getString(BackupWorker.KEY_MODE) == BackupWorker.MODE_RESTORE
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularWavyProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.size(72.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            when {
                                info?.state == WorkInfo.State.FAILED -> "Fehlgeschlagen: ${info.outputData.getString(BackupWorker.KEY_ERROR) ?: "unbekannt"}"
                                running -> if (restore) "Wiederherstellung läuft …" else "Backup läuft …"
                                else -> "Fertig"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "$done von $total geprüft · $copied ${if (restore) "wiederhergestellt" else "kopiert"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (running) {
                    OutlinedButton(onClick = { wm.cancelUniqueWork(BackupWorker.NAME) }, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
                } else {
                    Button(
                        onClick = { BackupWorker.start(context, BackupWorker.MODE_BACKUP) },
                        enabled = backup.treeUri != null,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().height(60.dp)
                    ) {
                        Icon(Icons.Outlined.Backup, null); Text("  Jetzt sichern")
                    }
                    OutlinedButton(
                        onClick = { BackupWorker.start(context, BackupWorker.MODE_RESTORE) },
                        enabled = backup.treeUri != null,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Icon(Icons.Outlined.Restore, null); Text("  Fehlende Dateien wiederherstellen")
                    }
                }
            }
        }
    }
}
