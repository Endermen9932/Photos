package app.lumen.photos.ui.screens.people

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.container
import app.lumen.photos.face.ReviewCandidate
import app.lumen.photos.ui.components.BackButton
import app.lumen.photos.ui.components.FaceImage
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.MediaThumbnail
import app.lumen.photos.ui.components.ShapeIcon
import app.lumen.photos.ui.components.crop
import app.lumen.photos.ui.navigation.LocalNavigator
import kotlinx.coroutines.launch
import kotlin.math.abs

private val Yes = Color(0xFF2E9E5B)
private val No = Color(0xFFD9433A)

/**
 * Tinder-style review: swipe right if the face is the person, left if not. Up to 50 cards,
 * the most uncertain ones first.
 */
@Composable
fun ReviewScreen(personId: Long) {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val persons by c.faces.persons.collectAsStateWithLifecycle()
    val name = persons.firstOrNull { it.id == personId }?.displayName ?: "diese Person"
    val cards = remember { mutableStateListOf<ReviewCandidate>() }
    var loaded by remember { mutableStateOf(false) }
    var total by remember { mutableIntStateOf(0) }
    var yes by remember { mutableIntStateOf(0) }
    var no by remember { mutableIntStateOf(0) }
    val history = remember { mutableStateListOf<Pair<ReviewCandidate, Boolean>>() }

    LaunchedEffect(personId) {
        val list = c.faces.reviewCandidates(personId, 50)
        cards.clear(); cards.addAll(list)
        total = list.size
        loaded = true
    }

    fun decide(card: ReviewCandidate, isYes: Boolean) {
        cards.remove(card)
        history.add(card to isYes)
        if (isYes) yes++ else no++
        haptics.performHapticFeedback(if (isYes) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
        scope.launch {
            if (isYes) c.faces.confirm(card.face, personId) else c.faces.rejectFace(card.face, personId)
        }
    }

    fun undo() {
        val (card, wasYes) = history.removeLastOrNull() ?: return
        if (wasYes) yes-- else no--
        cards.add(0, card)
        scope.launch { c.faces.undo(card.face, personId, wasYes) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton { nav.back() }
            Column(Modifier.weight(1f)) {
                Text("Ist das $name?", style = MaterialTheme.typography.titleLarge)
                if (total > 0) {
                    Text(
                        "${total - cards.size} von $total · $yes ja · $no nein",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (total > 0) {
            LinearWavyProgressIndicator(
                progress = { (total - cards.size).toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            when {
                !loaded -> LoadingIndicator(Modifier.size(72.dp))
                cards.isEmpty() -> Done(total, yes, no, name) { nav.back() }
                else -> {
                    // Draw the next two cards behind the top one.
                    val visible = cards.take(3).reversed()
                    visible.forEachIndexed { i, card ->
                        val depth = visible.size - 1 - i
                        key(card.face.id) {
                            SwipeCard(
                                card = card,
                                depth = depth,
                                name = name,
                                onDecision = { decide(card, it) },
                            )
                        }
                    }
                }
            }
        }

        if (loaded && cards.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 20.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = { cards.firstOrNull()?.let { decide(it, false) } },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = No, contentColor = Color.White),
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(72.dp)
                ) { Icon(Icons.Filled.Close, "Nein", Modifier.size(34.dp)) }
                FilledTonalIconButton(
                    onClick = { undo() },
                    enabled = history.isNotEmpty(),
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(52.dp)
                ) { Icon(Icons.AutoMirrored.Filled.Undo, "Rückgängig") }
                FilledIconButton(
                    onClick = { cards.firstOrNull()?.let { decide(it, true) } },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Yes, contentColor = Color.White),
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(72.dp)
                ) { Icon(Icons.Filled.Check, "Ja", Modifier.size(34.dp)) }
            }
        }
    }
}

@Composable
private fun SwipeCard(card: ReviewCandidate, depth: Int, name: String, onDecision: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val scale = remember { Animatable(1f - depth * 0.05f) }
    LaunchedEffect(depth) {
        scale.animateTo(1f - depth * 0.05f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val progress = (offsetX.value / (width * 0.35f)).coerceIn(-1f, 1f)
        Surface(
            shape = RoundedCornerShape(36.dp),
            shadowElevation = if (depth == 0) 10.dp else 2.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offsetX.value
                    translationY = offsetY.value + depth * 26f
                    rotationZ = progress * 14f
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .then(
                    if (depth == 0) Modifier.pointerInput(card.face.id) {
                        detectDragGestures(
                            onDragEnd = {
                                val decided = abs(offsetX.value) > width * 0.3f
                                scope.launch {
                                    if (decided) {
                                        val right = offsetX.value > 0
                                        launch { offsetY.animateTo(offsetY.value * 1.5f, tween(260)) }
                                        offsetX.animateTo(if (right) width * 1.6f else -width * 1.6f, tween(260))
                                        onDecision(right)
                                    } else {
                                        launch { offsetY.animateTo(0f, spring(dampingRatio = 0.6f)) }
                                        offsetX.animateTo(0f, spring(dampingRatio = 0.6f))
                                    }
                                }
                            }
                        ) { change, drag ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo(offsetX.value + drag.x)
                                offsetY.snapTo(offsetY.value + drag.y * 0.4f)
                            }
                        }
                    } else Modifier
                )
        ) {
            Box {
                FaceImage(card.face.crop(card.item.uri, 720), RoundedCornerShape(36.dp), Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))
                    )
                )
                // Whole photo for context.
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).size(96.dp)
                ) {
                    MediaThumbnail(card.item, shared = false, showBadges = false, size = 256, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Text(
                        if (card.alreadyAssigned) "Aktuell als $name eingeordnet" else "Vorschlag: vielleicht $name?",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "${Format.short(card.item.timestamp)} · KI-Ähnlichkeit ${(card.similarity * 100).toInt().coerceIn(0, 100)} %",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                // Stamps.
                if (progress > 0.05f) Stamp("JA", Yes, progress, Modifier.align(Alignment.TopStart).padding(24.dp).graphicsLayer { rotationZ = -12f })
                if (progress < -0.05f) Stamp("NEIN", No, -progress, Modifier.align(Alignment.TopStart).padding(24.dp).graphicsLayer { rotationZ = 12f })
            }
        }
    }
}

@Composable
private fun Stamp(text: String, color: Color, strength: Float, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.25f * strength),
        border = androidx.compose.foundation.BorderStroke(4.dp, color.copy(alpha = strength)),
        modifier = modifier.graphicsLayer { alpha = strength; scaleX = 0.8f + 0.2f * strength; scaleY = scaleX }
    ) {
        Text(
            text,
            color = color,
            fontSize = 38.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun Done(total: Int, yes: Int, no: Int, name: String, onClose: () -> Unit) {
    AnimatedContent(total, label = "done") { t ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ShapeIcon(Icons.Outlined.CheckCircle, size = 120.dp, shape = MaterialShapes.Cookie12Sided.toShape(), spin = true)
            Spacer(Modifier.height(20.dp))
            if (t == 0) {
                Text("Nichts zu prüfen", style = MaterialTheme.typography.headlineSmall)
                Text("Die KI ist sich bei allen Fotos von $name sicher.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Fertig!", style = MaterialTheme.typography.headlineMedium)
                Text("$yes bestätigt · $no entfernt", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Die Erkennung von $name ist jetzt genauer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onClose, shapes = ButtonDefaults.shapes()) { Text("Zurück") }
            Box(Modifier.size(1.dp).clip(CircleShape))
        }
    }
}
