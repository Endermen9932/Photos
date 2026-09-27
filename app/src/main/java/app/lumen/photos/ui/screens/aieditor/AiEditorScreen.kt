package app.lumen.photos.ui.screens.aieditor

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.AppContainer
import app.lumen.photos.container
import app.lumen.photos.edit.BackgroundEffect
import app.lumen.photos.edit.BackgroundEffects
import app.lumen.photos.edit.Inpainter
import app.lumen.photos.edit.Segmenter
import app.lumen.photos.edit.ToolKind
import app.lumen.photos.edit.ToolModel
import app.lumen.photos.ui.components.ConnectedToggleGroup
import app.lumen.photos.ui.navigation.LocalNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

private enum class AiTool(val label: String) { ERASE("Radierer"), BACKGROUND("Hintergrund") }

/** One brush stroke in image pixels. */
private class BrushStroke(val width: Float) {
    val points: SnapshotStateList<Offset> = mutableStateListOf()
}

/** Keeps the loaded ONNX sessions for the time the editor is open. */
private class Engines(private val c: AppContainer) {
    private var inpainter: Inpainter? = null
    private var segmenter: Segmenter? = null

    @Synchronized
    fun inpainter(model: ToolModel): Inpainter =
        inpainter?.takeIf { it.model == model } ?: Inpainter(model, c.models, c.settings.current.aiThreads).also { inpainter?.close(); inpainter = it }

    @Synchronized
    fun segmenter(model: ToolModel): Segmenter =
        segmenter?.takeIf { it.model == model } ?: Segmenter(model, c.models, c.settings.current.aiThreads).also { segmenter?.close(); segmenter = it }

    /** Runs inference; [close] waits for it, so a session is never closed while in use. */
    fun <T> use(block: Engines.() -> T): T = synchronized(this) { block() }

    @Synchronized
    fun close() {
        inpainter?.close(); inpainter = null
        segmenter?.close(); segmenter = null
    }
}

private const val MAX_HISTORY = 4

@Composable
fun AiEditorScreen(id: Long) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val item = remember(id) { c.media.byId(id) }
    val installed by c.models.installed.collectAsStateWithLifecycle()
    val inpaintActive by c.tools.active(ToolKind.INPAINT).collectAsStateWithLifecycle()
    val segmentActive by c.tools.active(ToolKind.SEGMENT).collectAsStateWithLifecycle()
    val inpaintModel = remember(installed, inpaintActive) { c.tools.usable(ToolKind.INPAINT) }
    val segmentModel = remember(installed, segmentActive) { c.tools.usable(ToolKind.SEGMENT) }

    val engines = remember { Engines(c) }
    DisposableEffect(Unit) { onDispose { c.scope.launch(Dispatchers.Default) { engines.close() } } }

    val history = remember { mutableStateListOf<Bitmap>() }
    val current = history.lastOrNull()
    var busy by remember { mutableStateOf<String?>("Foto wird geladen …") }
    var tool by remember { mutableStateOf(AiTool.ERASE) }
    val strokes = remember { mutableStateListOf<BrushStroke>() }
    var brushDp by remember { mutableFloatStateOf(34f) }
    var mask by remember { mutableStateOf<Bitmap?>(null) }
    var maskFor by remember { mutableStateOf<Bitmap?>(null) }
    var effect by remember { mutableStateOf<BackgroundEffect?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var blur by remember { mutableFloatStateOf(0.6f) }
    var confirmExit by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(id) {
        if (item == null) { nav.back(); return@LaunchedEffect }
        runCatching { withContext(Dispatchers.IO) { c.tools.decode(item) } }
            .onSuccess { history += it }
            .onFailure { Toast.makeText(context, "Foto konnte nicht geladen werden", Toast.LENGTH_LONG).show(); nav.back() }
        busy = null
    }

    fun push(bitmap: Bitmap) {
        history += bitmap
        while (history.size > MAX_HISTORY + 1) history.removeAt(0)
    }

    fun work(label: String, block: suspend CoroutineScope.() -> Unit) {
        if (busy != null) return
        busy = label
        scope.launch {
            try {
                withContext(Dispatchers.Default, block)
            } catch (e: OutOfMemoryError) {
                Toast.makeText(context, "Zu wenig Arbeitsspeicher", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Fehler: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            } finally {
                busy = null
            }
        }
    }

    fun erase() {
        val img = current ?: return
        val model = inpaintModel ?: return
        val drawn = strokes.map { s -> s.width to s.points.toList() }
        work("Objekt wird entfernt …") {
            val hole = strokeMask(img.width, img.height, drawn, widthScale = 1f, blur = 0f)
            val blend = strokeMask(img.width, img.height, drawn, widthScale = 1.35f, blur = 0.18f)
            val result = try {
                engines.use { inpainter(model).inpaint(img, hole, blend) }
            } finally {
                hole.recycle(); blend.recycle()
            }
            withContext(Dispatchers.Main) { push(result); strokes.clear() }
        }
    }

    fun applyEffect(e: BackgroundEffect) {
        val img = current ?: return
        val model = segmentModel ?: return
        effect = e
        work(if (maskFor === img) "Wird angewendet …" else "Motiv wird erkannt …") {
            val m = if (maskFor === img && mask != null) mask!! else engines.use { segmenter(model).segment(img) }.also { fresh ->
                withContext(Dispatchers.Main) { mask = fresh; maskFor = img }
            }
            val out = BackgroundEffects.apply(img, m, e, blur)
            withContext(Dispatchers.Main) { preview = out }
        }
    }

    fun discardPreview() {
        preview = null; effect = null
    }

    val changed = history.size > 1
    BackHandler(enabled = changed || preview != null) {
        if (preview != null) discardPreview() else confirmExit = true
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("KI-Editor") },
                navigationIcon = {
                    IconButton(onClick = { if (changed) confirmExit = true else nav.back() }) { Icon(Icons.Outlined.Close, "Schließen") }
                },
                actions = {
                    IconButton(onClick = { history.removeAt(history.lastIndex); discardPreview() }, enabled = changed && busy == null) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, "Rückgängig")
                    }
                    TextButton(
                        onClick = {
                            val img = current ?: return@TextButton
                            val it = item ?: return@TextButton
                            work("Wird gespeichert …") {
                                val saved = c.tools.saveCopy(it, img, "ki", png = img.hasAlpha())
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Als Kopie gespeichert: ${saved.name}", Toast.LENGTH_LONG).show()
                                    nav.back()
                                }
                            }
                        },
                        enabled = changed && busy == null && preview == null
                    ) { Text("Speichern") }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .onSizeChanged { boxSize = it },
                contentAlignment = Alignment.Center
            ) {
                val shown = preview ?: current
                if (shown != null) {
                    // Checkerboard-free: transparent parts simply show the dark background.
                    Image(shown.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                if (current != null && tool == AiTool.ERASE && preview == null && boxSize.width > 0) {
                    val scale = min(boxSize.width / current.width.toFloat(), boxSize.height / current.height.toFloat())
                    val dx = (boxSize.width - current.width * scale) / 2f
                    val dy = (boxSize.height - current.height * scale) / 2f
                    fun toImage(p: Offset) = Offset((p.x - dx) / scale, (p.y - dy) / scale)
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(current, scale) {
                                detectTapGestures { p ->
                                    if (busy != null) return@detectTapGestures
                                    strokes += BrushStroke(with(density) { brushDp.dp.toPx() } / scale).also { it.points += toImage(p) }
                                }
                            }
                            .pointerInput(current, scale) {
                                detectDragGestures(
                                    onDragStart = { p ->
                                        if (busy == null) strokes += BrushStroke(with(density) { brushDp.dp.toPx() } / scale).also { it.points += toImage(p) }
                                    },
                                    onDrag = { change, _ ->
                                        if (busy == null) strokes.lastOrNull()?.points?.add(toImage(change.position))
                                    }
                                )
                            }
                    ) {
                        for (s in strokes) {
                            if (s.points.isEmpty()) continue
                            val path = Path()
                            s.points.forEachIndexed { i, p ->
                                val x = p.x * scale + dx
                                val y = p.y * scale + dy
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            if (s.points.size == 1) path.lineTo(s.points[0].x * scale + dx + 0.1f, s.points[0].y * scale + dy)
                            drawPath(
                                path,
                                color = Color(0xFFFF4D6D).copy(alpha = 0.55f),
                                style = Stroke(width = s.width * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                }
                busy?.let { label ->
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            LoadingIndicator()
                            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConnectedToggleGroup(
                        options = AiTool.entries,
                        selected = tool,
                        onSelect = { if (busy == null) { discardPreview(); tool = it } },
                        label = { it.label },
                    )
                    AnimatedContent(tool, label = "aiTool") { t ->
                        when (t) {
                            AiTool.ERASE -> ToolPanel(
                                model = inpaintModel,
                                kind = ToolKind.INPAINT,
                                onGetModel = { nav.models() },
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "Male über alles, was verschwinden soll.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Pinsel", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
                                        Slider(value = brushDp, onValueChange = { brushDp = it }, valueRange = 10f..90f, modifier = Modifier.weight(1f))
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilledTonalButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty() && busy == null) {
                                            Icon(Icons.Outlined.Delete, null); Text(" Leeren")
                                        }
                                        Button(onClick = { erase() }, enabled = strokes.isNotEmpty() && busy == null, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                                            Icon(Icons.Outlined.AutoFixHigh, null); Text(" Entfernen")
                                        }
                                    }
                                }
                            }
                            AiTool.BACKGROUND -> ToolPanel(
                                model = segmentModel,
                                kind = ToolKind.SEGMENT,
                                onGetModel = { nav.models() },
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        BackgroundEffect.entries.forEach { e ->
                                            FilterChip(
                                                selected = effect == e,
                                                enabled = busy == null,
                                                onClick = { applyEffect(e) },
                                                label = { Text(e.label) },
                                            )
                                        }
                                    }
                                    if (effect == BackgroundEffect.BLUR) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Stärke", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
                                            Slider(
                                                value = blur,
                                                onValueChange = { blur = it },
                                                onValueChangeFinished = { applyEffect(BackgroundEffect.BLUR) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                    if (effect == BackgroundEffect.CUTOUT) {
                                        Text(
                                            "Freigestellte Bilder werden als PNG mit transparentem Hintergrund gespeichert.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (preview != null) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilledTonalButton(onClick = { discardPreview() }, enabled = busy == null) { Text("Verwerfen") }
                                            Button(
                                                onClick = { preview?.let { push(it) }; preview = null; effect = null },
                                                enabled = busy == null,
                                                shapes = ButtonDefaults.shapes(),
                                                modifier = Modifier.weight(1f)
                                            ) { Icon(Icons.Outlined.Check, null); Text(" Übernehmen") }
                                        }
                                    } else {
                                        Text(
                                            "Wähle einen Effekt – das Motiv wird automatisch erkannt.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Änderungen verwerfen?") },
            text = { Text("Die Bearbeitung ist noch nicht gespeichert.") },
            confirmButton = { Button(onClick = { confirmExit = false; nav.back() }) { Text("Verwerfen") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Weiter bearbeiten") } },
        )
    }
}

/** Shows the tool's controls, or a hint to download a model first. */
@Composable
private fun ToolPanel(model: ToolModel?, kind: ToolKind, onGetModel: () -> Unit, content: @Composable () -> Unit) {
    if (model == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Für „${kind.title}“ wird ein KI-Modell benötigt. Es wird einmal geladen und läuft danach offline.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start
            )
            Button(onClick = onGetModel, shapes = ButtonDefaults.shapes()) { Icon(Icons.Outlined.CloudDownload, null); Text(" Modell auswählen") }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Modell: ${model.name} · ${model.tier}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onGetModel) { Text("Ändern") }
        }
        Spacer(Modifier.width(0.dp))
        content()
    }
}

/** Renders brush strokes (in image pixels) into an ALPHA_8 mask of the image size. */
private fun strokeMask(width: Int, height: Int, strokes: List<Pair<Float, List<Offset>>>, widthScale: Float, blur: Float): Bitmap {
    val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
    val canvas = android.graphics.Canvas(mask)
    for ((w, points) in strokes) {
        if (points.isEmpty()) continue
        val strokeWidth = w * widthScale
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = android.graphics.Color.BLACK
            if (blur > 0f) maskFilter = BlurMaskFilter(strokeWidth * blur, BlurMaskFilter.Blur.NORMAL)
        }
        val path = android.graphics.Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        if (points.size == 1) path.lineTo(points[0].x + 0.1f, points[0].y)
        canvas.drawPath(path, paint)
    }
    return mask
}
