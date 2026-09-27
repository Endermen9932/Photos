package app.lumen.photos.ui.screens.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import app.lumen.photos.ui.screens.settings.RestoreFromBackupButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lumen.photos.container
import app.lumen.photos.ui.components.MorphingBlob
import app.lumen.photos.ui.components.ShapeIcon
import app.lumen.photos.ui.navigation.LocalNavigator
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var denied by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { show = true }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.READ_MEDIA_IMAGES] == true ||
            result[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
        if (granted) {
            scope.launch {
                context.container.settings.update { it.copy(onboardingDone = true) }
                context.container.media.start()
                nav.finishOnboarding()
            }
        } else {
            denied = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            MorphingBlob(
                colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                modifier = Modifier.size(220.dp)
            )
            ShapeIcon(
                Icons.Outlined.PhotoLibrary,
                shape = MaterialShapes.Circle.toShape(),
                container = MaterialTheme.colorScheme.surface,
                content = MaterialTheme.colorScheme.primary,
                size = 96.dp
            )
        }
        Spacer(Modifier.height(28.dp))
        Text("Willkommen bei Lumen", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Deine Galerie – schnell, schön und vollständig privat. Ohne Google-Dienste, ohne Cloud.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        AnimatedVisibility(show, enter = fadeIn() + slideInVertically { it / 3 }) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Feature(Icons.Outlined.AutoAwesome, "KI-Suche auf dem Gerät", "Finde Fotos mit Worten wie „Hund am Strand“ – alles läuft lokal auf dem Tensor-Chip.", MaterialShapes.Cookie9Sided.toShape())
                Feature(Icons.Outlined.Compress, "Speicher optimieren", "Skaliere die ganze Galerie mit einem Tipp auf Full HD – mit Vorschau der Qualität.", MaterialShapes.Clover4Leaf.toShape())
                Feature(Icons.Outlined.Lock, "Privat by Design", "Keine Konten, kein Tracking. Das Internet wird nur für den einmaligen Modell-Download genutzt.", MaterialShapes.Pentagon.toShape())
            }
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        if (denied) {
            Text(
                "Ohne Zugriff auf deine Fotos kann Lumen nichts anzeigen.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                )
            }) { Text("App-Einstellungen öffnen") }
        }
        Button(
            onClick = {
                launcher.launch(
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.ACCESS_MEDIA_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shapes = ButtonDefaults.shapes(),
        ) {
            Text("Los geht's", style = MaterialTheme.typography.titleMedium)
        }
        RestoreFromBackupButton(Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String, shape: androidx.compose.ui.graphics.Shape) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShapeIcon(icon, shape = shape, size = 52.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
