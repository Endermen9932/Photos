package app.lumen.photos.ui.screens.editor

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lumen.photos.container
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.optimize.ExifCopier
import app.lumen.photos.ui.components.ConnectedToggleGroup
import app.lumen.photos.ui.navigation.LocalNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private data class Adjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
)

private data class Filter(val name: String, val matrix: () -> ColorMatrix)

private val filters = listOf(
    Filter("Original") { ColorMatrix() },
    Filter("Lebendig") { ColorMatrix().apply { setSaturation(1.35f) } },
    Filter("Warm") { ColorMatrix(floatArrayOf(1.08f, 0f, 0f, 0f, 12f, 0f, 1.0f, 0f, 0f, 4f, 0f, 0f, 0.9f, 0f, -8f, 0f, 0f, 0f, 1f, 0f)) },
    Filter("Kühl") { ColorMatrix(floatArrayOf(0.92f, 0f, 0f, 0f, -6f, 0f, 1.0f, 0f, 0f, 2f, 0f, 0f, 1.1f, 0f, 14f, 0f, 0f, 0f, 1f, 0f)) },
    Filter("S/W") { ColorMatrix().apply { setSaturation(0f) } },
    Filter("Noir") {
        ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix(floatArrayOf(1.35f, 0f, 0f, 0f, -40f, 0f, 1.35f, 0f, 0f, -40f, 0f, 0f, 1.35f, 0f, -40f, 0f, 0f, 0f, 1f, 0f)))
        }
    },
    Filter("Sepia") {
        ColorMatrix(floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
    },
    Filter("Vintage") {
        ColorMatrix().apply {
            setSaturation(0.7f)
            postConcat(ColorMatrix(floatArrayOf(0.95f, 0f, 0f, 0f, 20f, 0f, 0.92f, 0f, 0f, 14f, 0f, 0f, 0.8f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)))
        }
    },
)

private fun buildMatrix(filter: Filter, a: Adjust): ColorMatrix {
    val m = filter.matrix()
    // Saturation
    m.postConcat(ColorMatrix().apply { setSaturation(1f + a.saturation) })
    // Contrast around mid grey + brightness
    val c = 1f + a.contrast
    val t = 128f * (1f - c) + a.brightness * 100f
    m.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    // Warmth (red/blue) and tint (green/magenta)
    val w = a.warmth * 30f
    val g = a.tint * 25f
    m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, w, 0f, 1f, 0f, 0f, g, 0f, 0f, 1f, 0f, -w, 0f, 0f, 0f, 1f, 0f)))
    return m
}

private fun render(source: Bitmap, matrix: ColorMatrix, rotation: Int, flip: Boolean): Bitmap {
    val m = Matrix().apply {
        postRotate(rotation.toFloat())
        if (flip) postScale(-1f, 1f)
    }
    val swap = rotation % 180 != 0
    val w = if (swap) source.height else source.width
    val h = if (swap) source.width else source.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.translate(w / 2f, h / 2f)
    canvas.concat(m)
    canvas.translate(-source.width / 2f, -source.height / 2f)
    canvas.drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
    return out
}

@Composable
fun EditorScreen(id: Long) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val item: MediaItem? = remember(id) { c.media.byId(id) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var filter by remember { mutableStateOf(filters[0]) }
    var adjust by remember { mutableStateOf(Adjust()) }
    var rotation by remember { mutableIntStateOf(0) }
    var flip by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("Filter") }
    var saving by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }

    LaunchedEffect(item) {
        val it = item ?: return@LaunchedEffect
        preview = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, it.uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = minOf(1f, 1600f / maxOf(info.size.width, info.size.height))
                    d.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
            }.getOrNull()
        }
    }

    val matrix = remember(filter, adjust) { buildMatrix(filter, adjust) }
    val colorFilter = remember(matrix, showOriginal) {
        if (showOriginal) null else androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(matrix.array))
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.Filled.Close, "Abbrechen", tint = Color.White) }
            Text("Bearbeiten", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(
                enabled = !saving && item != null,
                shapes = ButtonDefaults.shapes(),
                onClick = {
                    val it = item ?: return@Button
                    saving = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { saveCopy(context, it, matrix, rotation, flip) }
                        saving = false
                        Toast.makeText(context, if (ok) "Als Kopie gespeichert" else "Speichern fehlgeschlagen", Toast.LENGTH_SHORT).show()
                        if (ok) { c.media.reload(); nav.back() }
                    }
                }
            ) { Text(if (saving) "Speichert …" else "Kopie speichern") }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        showOriginal = true
                        tryAwaitRelease()
                        showOriginal = false
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            val bmp = preview
            if (bmp == null) LoadingIndicator()
            else Image(
                bmp.asImageBitmap(),
                contentDescription = null,
                colorFilter = colorFilter,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .graphicsLayer {
                        rotationZ = rotation.toFloat()
                        scaleX = if (flip) -1f else 1f
                        val swap = rotation % 180 != 0
                        if (swap) {
                            val s = minOf(size.width / size.height, size.height / size.width)
                            scaleX *= s; scaleY = s
                        }
                    }
            )
            if (showOriginal) {
                Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)) {
                    Text("Original", color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(Modifier.navigationBarsPadding().padding(vertical = 12.dp)) {
                ConnectedToggleGroup(
                    options = listOf("Filter", "Anpassen", "Drehen"),
                    selected = tab,
                    onSelect = { tab = it },
                    label = { it },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                AnimatedContent(tab, label = "editor-tab") { t ->
                    when (t) {
                        "Filter" -> LazyRow(
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filters, key = { it.name }) { f ->
                                val sel = f == filter
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Surface(
                                        onClick = { filter = f },
                                        shape = RoundedCornerShape(if (sel) 26.dp else 16.dp),
                                        border = if (sel) androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                                        modifier = Modifier.size(76.dp)
                                    ) {
                                        preview?.let { b ->
                                            Image(
                                                b.asImageBitmap(), null,
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                                                    androidx.compose.ui.graphics.ColorMatrix(f.matrix().array)
                                                )
                                            )
                                        }
                                    }
                                    Text(f.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                        "Anpassen" -> Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            AdjustSlider("Helligkeit", adjust.brightness) { adjust = adjust.copy(brightness = it) }
                            AdjustSlider("Kontrast", adjust.contrast) { adjust = adjust.copy(contrast = it) }
                            AdjustSlider("Sättigung", adjust.saturation) { adjust = adjust.copy(saturation = it) }
                            AdjustSlider("Wärme", adjust.warmth) { adjust = adjust.copy(warmth = it) }
                            AdjustSlider("Tönung", adjust.tint) { adjust = adjust.copy(tint = it) }
                        }
                        else -> Row(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                        ) {
                            Button(onClick = { rotation = (rotation + 90) % 360 }, shapes = ButtonDefaults.shapes()) {
                                Icon(Icons.Outlined.RotateRight, null); Text("  Drehen")
                            }
                            Button(onClick = { flip = !flip }, shapes = ButtonDefaults.shapes()) {
                                Icon(Icons.Outlined.Flip, null); Text("  Spiegeln")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(90.dp))
        Slider(value = v, onValueChange = { v = it; onChange(it) }, valueRange = -1f..1f, modifier = Modifier.weight(1f))
    }
}

/** Renders the edit at full resolution and stores it as a new file next to the original. */
private fun saveCopy(context: android.content.Context, item: MediaItem, matrix: ColorMatrix, rotation: Int, flip: Boolean): Boolean = runCatching {
    val resolver = context.contentResolver
    val source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { d, _, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val rendered = render(source, matrix, rotation, flip)
    source.recycle()
    val bytes = ByteArrayOutputStream().use { rendered.compress(Bitmap.CompressFormat.JPEG, 94, it); it.toByteArray() }
    val attributes = runCatching {
        resolver.openInputStream(runCatching { MediaStore.setRequireOriginal(item.uri) }.getOrDefault(item.uri))?.use { ExifCopier.readAttributes(it) }
    }.getOrNull().orEmpty()
    val finalBytes = ExifCopier.spliceIntoJpeg(bytes, attributes, rendered.width, rendered.height, context.cacheDir)
    rendered.recycle()
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, item.name.substringBeforeLast('.') + "_bearbeitet.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, item.relativePath.ifBlank { "Pictures/" })
        put(MediaStore.MediaColumns.DATE_TAKEN, item.timestamp)
    }
    val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
    resolver.openOutputStream(uri)!!.use { it.write(finalBytes) }
    true
}.getOrDefault(false)
