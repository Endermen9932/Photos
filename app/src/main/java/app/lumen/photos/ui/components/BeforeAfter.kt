package app.lumen.photos.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Before/after comparison: drag the divider to compare, pinch or double tap to zoom in and look
 * at compression artefacts in detail. Both images are always transformed together.
 */
@Composable
fun BeforeAfter(
    before: ImageBitmap,
    after: ImageBitmap,
    beforeLabel: String,
    afterLabel: String,
    modifier: Modifier = Modifier,
) {
    var split by remember { mutableFloatStateOf(0.5f) }
    val scale = remember { Animatable(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        fun clampOffset(o: Offset, s: Float): Offset {
            val maxX = (width * (s - 1)) / 2
            val maxY = (height * (s - 1)) / 2
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }
        val transform = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationX = offset.x
                translationY = offset.y
            }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale.value * zoom).coerceIn(1f, 10f)
                        scope.launch { scale.snapTo(newScale) }
                        val center = Offset(width / 2, height / 2)
                        offset = clampOffset((offset + (centroid - center) * (1 - zoom)) * 1f + pan, newScale)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { tap ->
                        scope.launch {
                            if (scale.value > 1.5f) {
                                offset = Offset.Zero
                                scale.animateTo(1f)
                            } else {
                                val target = 4f
                                val center = Offset(width / 2, height / 2)
                                offset = clampOffset((center - tap) * (target - 1), target)
                                scale.animateTo(target)
                            }
                        }
                    })
                }
        ) {
            Image(before, contentDescription = "Original", contentScale = ContentScale.Fit, modifier = transform)
            Image(
                after,
                contentDescription = "Komprimiert",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(left = size.width * split) { this@drawWithContent.drawContent() }
                    }
                    .then(transform)
            )
        }
        // Labels.
        Label(beforeLabel, Modifier.align(Alignment.TopStart).padding(12.dp))
        Label(afterLabel, Modifier.align(Alignment.TopEnd).padding(12.dp))

        // Divider with handle.
        val x = (width * split).roundToInt()
        Box(
            Modifier
                .offset { IntOffset(x - 24.dp.roundToPx(), 0) }
                .width(48.dp)
                .fillMaxHeight()
                .pointerInput(width) {
                    detectHorizontalDragGestures { change, dx ->
                        change.consume()
                        split = (split + dx / width).coerceIn(0f, 1f)
                    }
                }
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.9f))
            )
            Surface(
                shape = CircleShape,
                color = Color.White,
                contentColor = Color.Black,
                shadowElevation = 4.dp,
                modifier = Modifier.align(Alignment.Center).size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.CompareArrows, "Vergleichen", Modifier.size(22.dp))
                }
            }
        }
        if (scale.value <= 1.01f) {
            Label(
                "Doppeltippen oder zoomen für Details",
                Modifier.align(Alignment.BottomCenter).padding(12.dp),
                subtle = true
            )
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier, subtle: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = if (subtle) 0.35f else 0.55f),
        contentColor = Color.White,
        modifier = modifier
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}
