package app.lumen.photos.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import kotlinx.coroutines.delay

/** A slowly rotating shape that morphs between Material 3 Expressive shapes. */
@Composable
fun MorphingBlob(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    intervalMs: Long = 2200,
) {
    val shapes = remember {
        listOf(
            MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.SoftBurst,
            MaterialShapes.Pentagon, MaterialShapes.Flower, MaterialShapes.Cookie12Sided, MaterialShapes.Puffy,
        )
    }
    var index by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 60f))
            index = (index + 1) % shapes.size
            progress.snapTo(0f)
        }
    }
    val morphs = remember { shapes.indices.map { Morph(shapes[it], shapes[(it + 1) % shapes.size]) } }
    val rotation by rememberInfiniteTransition(label = "blob").animateFloat(
        0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "rot"
    )
    val androidPath = remember { android.graphics.Path() }
    Canvas(modifier) {
        val path = morphs[index].toPath(progress.value, androidPath).asComposePath()
        val s = size.minDimension
        rotate(rotation) {
            translate((size.width - s) / 2f, (size.height - s) / 2f) {
                scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                    drawPath(path, Brush.linearGradient(colors, start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(1f, 1f)))
                }
            }
        }
    }
}
