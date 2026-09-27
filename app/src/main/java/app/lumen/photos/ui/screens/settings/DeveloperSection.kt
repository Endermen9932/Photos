package app.lumen.photos.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.container
import app.lumen.photos.data.backup.DevBackup
import app.lumen.photos.data.backup.DevBackupInfo
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.SectionTitle
import kotlinx.coroutines.launch

/** Last section of the settings: developer mode with the reinstall-proof backup. */
@Composable
fun DeveloperSection() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val state by c.devBackup.state.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    val hasAccess = remember(tick) { DevBackup.hasAccess() }
    var enableAfterAccess by remember { mutableStateOf(false) }
    var askRestore by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }

    fun enable() = scope.launch {
        c.settings.update { it.copy(developerMode = true) }
        c.devBackup.refreshInfo()
        // A backup of an earlier installation: offer to restore it before it gets overwritten.
        if (c.devBackup.state.value.foreign) askRestore = true
        else c.devBackup.backupNow()
    }

    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tick++
        c.devBackup.refreshInfo()
        if (enableAfterAccess && DevBackup.hasAccess()) enable()
        enableAfterAccess = false
    }

    SectionTitle("Entwickler")
    ListItem(
        headlineContent = { Text("Entwicklermodus") },
        supportingContent = {
            Text("Sichert Modelle, Suchindex, Gesichter & Namen und alle Einstellungen in „${DevBackup.FOLDER_LABEL}“. Nach einer Neuinstallation ist mit einem Tipp alles wieder da – ohne neu herunterzuladen oder zu indexieren.")
        },
        trailingContent = {
            Switch(checked = s.developerMode, onCheckedChange = null)
        },
        modifier = Modifier.clickable {
            if (s.developerMode) {
                scope.launch { c.settings.update { it.copy(developerMode = false) } }
            } else if (DevBackup.hasAccess()) {
                enable()
            } else {
                enableAfterAccess = true
                accessLauncher.launch(DevBackup.accessIntent(context))
            }
        }
    )
    AnimatedVisibility(s.developerMode) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hasAccess) {
                Text(
                    "Für das Backup braucht Lumen „Zugriff auf alle Dateien“ – nur so kann eine neue Installation die Dateien wieder lesen.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { accessLauncher.launch(DevBackup.accessIntent(context)) }) { Text("Zugriff erlauben") }
            } else {
                Text(backupSummary(state.info), style = MaterialTheme.typography.bodyMedium)
                if (state.running) {
                    Text(state.step ?: "Sichern …", style = MaterialTheme.typography.labelMedium)
                    val p = state.progress
                    if (p != null) LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.error?.let {
                    Text("Fehler: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.foreign) {
                    Text(
                        "Dieses Backup stammt von einer früheren Installation. Automatische Sicherungen sind pausiert, bis du es wiederherstellst oder überschreibst.",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (state.foreign) confirmOverwrite = true
                            else scope.launch { c.devBackup.backupNow() }
                        },
                        enabled = !state.running,
                        shapes = ButtonDefaults.shapes(),
                    ) { Icon(Icons.Outlined.Backup, null); Text(" Jetzt sichern") }
                    OutlinedButton(onClick = { askRestore = true }, enabled = !state.running && state.info != null) {
                        Icon(Icons.Outlined.Restore, null); Text(" Wiederherstellen")
                    }
                }
                Text(
                    "Wird automatisch aktualisiert, sobald sich Einstellungen, Modelle, Index oder Personen ändern. " +
                        "Nach dem Neuinstallieren: beim Start „Backup wiederherstellen“ tippen oder hier den Entwicklermodus einschalten.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (askRestore) {
        AlertDialog(
            onDismissRequest = { askRestore = false },
            title = { Text("Backup wiederherstellen?") },
            text = {
                Text(backupSummary(state.info) + "\n\nModelle, Suchindex, Gesichter, Namen und Einstellungen werden durch den Stand des Backups ersetzt. Die App startet danach neu.")
            },
            confirmButton = { Button(onClick = { askRestore = false; restoring = true }) { Text("Wiederherstellen") } },
            dismissButton = { TextButton(onClick = { askRestore = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("Backup überschreiben?") },
            text = { Text("Das vorhandene Backup einer früheren Installation wird durch den aktuellen Stand ersetzt.") },
            confirmButton = {
                Button(onClick = { confirmOverwrite = false; scope.launch { c.devBackup.backupNow() } }) { Text("Überschreiben") }
            },
            dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("Abbrechen") } },
        )
    }
    if (restoring) RestoreDialog(onFinished = { restoring = false })
}

fun backupSummary(info: DevBackupInfo?): String =
    if (info == null) "Noch keine Sicherung in ${DevBackup.FOLDER_LABEL}."
    else "Stand: ${Format.full(info.createdAt)} · ${Format.bytes(info.modelBytes + info.databaseBytes)} · " +
        "${info.models.size} ${if (info.models.size == 1) "Modell" else "Modelle"} · Lumen ${info.appVersion}"

/** Runs the restore with a progress dialog and restarts the app when it is done. */
@Composable
fun RestoreDialog(onFinished: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val state by c.devBackup.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        val result = c.devBackup.restore()
        if (result.isSuccess) {
            DevBackup.restartApp(context)
        } else {
            Toast.makeText(context, "Wiederherstellen fehlgeschlagen: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            onFinished()
        }
    }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Wird wiederhergestellt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.step ?: "Vorbereiten …")
                val p = state.progress
                if (p != null) LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
    )
}

/**
 * Button for the onboarding: after reinstalling, restore everything from the developer backup
 * (asks for "Zugriff auf alle Dateien" first).
 */
@Composable
fun RestoreFromBackupButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = context.container
    var confirm by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    fun check() {
        c.devBackup.refreshInfo()
        if (DevBackup.backupExists()) confirm = true
        else Toast.makeText(context, "Kein Backup in ${DevBackup.FOLDER_LABEL} gefunden", Toast.LENGTH_LONG).show()
    }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (DevBackup.hasAccess()) check()
    }
    TextButton(
        onClick = { if (DevBackup.hasAccess()) check() else accessLauncher.launch(DevBackup.accessIntent(context)) },
        modifier = modifier,
    ) { Icon(Icons.Outlined.Restore, null); Text(" Backup wiederherstellen (Entwicklermodus)") }

    if (confirm) {
        val state by c.devBackup.state.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Backup gefunden") },
            text = { Text(backupSummary(state.info) + "\n\nAlles wiederherstellen? Die App startet danach neu.") },
            confirmButton = { Button(onClick = { confirm = false; restoring = true }) { Text("Wiederherstellen") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } },
        )
    }
    if (restoring) RestoreDialog(onFinished = { restoring = false })
}
