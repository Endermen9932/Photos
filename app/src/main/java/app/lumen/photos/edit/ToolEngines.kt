package app.lumen.photos.edit

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import app.lumen.photos.ai.ModelManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Common ONNX session handling for the image tools. */
abstract class ToolEngine(val model: ToolModel, manager: ModelManager, threads: Int) : AutoCloseable {
    protected val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    protected val session: OrtSession = env.createSession(
        manager.file(model, model.file).absolutePath,
        OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads.coerceIn(1, 8))
        }
    )
    protected val inputNames: List<String> = session.inputNames.toList()

    override fun close() = session.close()

    protected fun floatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    protected val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
}

/**
 * 4× super resolution, tile by tile so any image size fits into memory. Each tile carries a
 * margin of context that is cut away again, so there are no visible seams.
 */
class Upscaler(model: ToolModel, manager: ModelManager, threads: Int) : ToolEngine(model, manager, threads) {
    private val tile = model.tile.takeIf { it > 0 } ?: 128
    private val pad = if (tile <= 64) 6 else 8
    private val scale = 4

    /** Number of model runs needed for a [width] × [height] image (for progress and ETA). */
    fun tileCount(width: Int, height: Int): Int {
        val core = tile - 2 * pad
        return ((width + core - 1) / core) * ((height + core - 1) / core)
    }

    /**
     * Upscales [source] so the result is [factor] (1..4) times as large. The model always works
     * at 4×; smaller factors are downsampled from that, which also makes them extra crisp.
     */
    fun upscale(source: Bitmap, factor: Int, onTile: (done: Int, total: Int) -> Unit, isCancelled: () -> Boolean): Bitmap {
        val w = source.width
        val h = source.height
        val padded = padToTile(source)
        val out = Bitmap.createBitmap(w * factor, h * factor, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val core = tile - 2 * pad
        val total = tileCount(w, h)
        val plane = tile * tile
        val input = floatBuffer(3 * plane)
        val pixels = IntArray(plane)
        val outTile = tile * scale
        val outPixels = IntArray(outTile * outTile)
        val tileBitmap = Bitmap.createBitmap(outTile, outTile, Bitmap.Config.ARGB_8888)
        var done = 0
        try {
            var y = 0
            while (y < h) {
                var x = 0
                val ch = min(core, h - y)
                while (x < w) {
                    check(!isCancelled()) { "Abgebrochen" }
                    val cw = min(core, w - x)
                    val sx = (x - pad).coerceIn(0, padded.width - tile)
                    val sy = (y - pad).coerceIn(0, padded.height - tile)
                    padded.getPixels(pixels, 0, tile, sx, sy, tile, tile)
                    for (i in 0 until plane) {
                        val c = pixels[i]
                        input.put(i, ((c shr 16) and 0xFF) / 255f)
                        input.put(plane + i, ((c shr 8) and 0xFF) / 255f)
                        input.put(2 * plane + i, (c and 0xFF) / 255f)
                    }
                    input.rewind()
                    OnnxTensor.createTensor(env, input, longArrayOf(1, 3, tile.toLong(), tile.toLong())).use { t ->
                        session.run(mapOf(inputNames.first() to t)).use { r ->
                            val fb = (r.get(0) as OnnxTensor).floatBuffer
                            val op = outTile * outTile
                            for (i in 0 until op) {
                                val rr = (fb.get(i) * 255f).roundToInt().coerceIn(0, 255)
                                val gg = (fb.get(op + i) * 255f).roundToInt().coerceIn(0, 255)
                                val bb = (fb.get(2 * op + i) * 255f).roundToInt().coerceIn(0, 255)
                                outPixels[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                            }
                        }
                    }
                    tileBitmap.setPixels(outPixels, 0, outTile, 0, 0, outTile, outTile)
                    val src = Rect((x - sx) * scale, (y - sy) * scale, (x - sx + cw) * scale, (y - sy + ch) * scale)
                    val dst = Rect(x * factor, y * factor, (x + cw) * factor, (y + ch) * factor)
                    canvas.drawBitmap(tileBitmap, src, dst, filter)
                    onTile(++done, total)
                    x += core
                }
                y += core
            }
        } catch (e: Throwable) {
            out.recycle()
            throw e
        } finally {
            tileBitmap.recycle()
            if (padded !== source) padded.recycle()
        }
        return out
    }

    /** Images smaller than one tile are extended by repeating their last row/column. */
    private fun padToTile(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w >= tile && h >= tile) return source
        val pw = max(w, tile)
        val ph = max(h, tile)
        val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawBitmap(source, 0f, 0f, null)
            if (pw > w) drawBitmap(source, Rect(w - 1, 0, w, h), Rect(w, 0, pw, h), null)
            if (ph > h) drawBitmap(source, Rect(0, h - 1, w, h), Rect(0, h, w, ph), null)
            if (pw > w && ph > h) drawBitmap(source, Rect(w - 1, h - 1, w, h), Rect(w, h, pw, ph), null)
        }
        return out
    }
}

/**
 * Removes the painted area. Only a square around the mask is sent through the model (at 512 px),
 * so the rest of the photo keeps its full resolution untouched.
 */
class Inpainter(model: ToolModel, manager: ModelManager, threads: Int) : ToolEngine(model, manager, threads) {

    /**
     * [hole] (ALPHA_8, image size): what to remove. [blend] (ALPHA_8): slightly larger, soft
     * version used to blend the result back in.
     */
    fun inpaint(image: Bitmap, hole: Bitmap, blend: Bitmap): Bitmap {
        val bounds = alphaBounds(hole) ?: return image.copy(Bitmap.Config.ARGB_8888, true)
        val crop = contextSquare(bounds, image.width, image.height)
        val size = SIZE
        val imgCrop = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(imgCrop).drawBitmap(image, crop, Rect(0, 0, size, size), filter)
        val maskCrop = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        Canvas(maskCrop).drawBitmap(hole, crop, Rect(0, 0, size, size), filter)
        val pixels = IntArray(size * size)
        imgCrop.getPixels(pixels, 0, size, 0, 0, size, size)
        val maskBytes = alphaBytes(maskCrop)
        imgCrop.recycle(); maskCrop.recycle()

        val result = when (model.format) {
            ToolFormat.MIGAN -> runMigan(pixels, maskBytes, size)
            else -> runLama(pixels, maskBytes, size)
        }
        val filled = Bitmap.createBitmap(result, size, size, Bitmap.Config.ARGB_8888)

        // Scale the filled square back and blend it in through the soft mask.
        val patch = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
        Canvas(patch).apply {
            drawBitmap(filled, null, Rect(0, 0, crop.width(), crop.height()), filter)
            drawBitmap(blend, crop, Rect(0, 0, crop.width(), crop.height()), Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        }
        filled.recycle()
        val out = image.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(patch, crop.left.toFloat(), crop.top.toFloat(), null)
        patch.recycle()
        return out
    }

    private fun runLama(pixels: IntArray, mask: ByteArray, size: Int): IntArray {
        val plane = size * size
        val img = floatBuffer(3 * plane)
        val msk = floatBuffer(plane)
        for (i in 0 until plane) {
            val c = pixels[i]
            img.put(i, ((c shr 16) and 0xFF) / 255f)
            img.put(plane + i, ((c shr 8) and 0xFF) / 255f)
            img.put(2 * plane + i, (c and 0xFF) / 255f)
            msk.put(i, if ((mask[i].toInt() and 0xFF) > 0) 1f else 0f)
        }
        img.rewind(); msk.rewind()
        val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
        OnnxTensor.createTensor(env, img, shape).use { ti ->
            OnnxTensor.createTensor(env, msk, longArrayOf(1, 1, size.toLong(), size.toLong())).use { tm ->
                session.run(mapOf(nameOf("image", 0) to ti, nameOf("mask", 1) to tm)).use { r ->
                    val fb = (r.get(0) as OnnxTensor).floatBuffer
                    // LaMa returns 0..255; be tolerant if a variant returns 0..1.
                    var peak = 0f
                    for (i in 0 until minOf(fb.capacity(), 4096)) peak = max(peak, fb.get(i))
                    val mul = if (peak <= 1.5f) 255f else 1f
                    return IntArray(plane) { i ->
                        val rr = (fb.get(i) * mul).roundToInt().coerceIn(0, 255)
                        val gg = (fb.get(plane + i) * mul).roundToInt().coerceIn(0, 255)
                        val bb = (fb.get(2 * plane + i) * mul).roundToInt().coerceIn(0, 255)
                        (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                    }
                }
            }
        }
    }

    private fun runMigan(pixels: IntArray, mask: ByteArray, size: Int): IntArray {
        val plane = size * size
        val img = ByteBuffer.allocateDirect(3 * plane).order(ByteOrder.nativeOrder())
        val msk = ByteBuffer.allocateDirect(plane).order(ByteOrder.nativeOrder())
        for (i in 0 until plane) {
            val c = pixels[i]
            img.put(i, ((c shr 16) and 0xFF).toByte())
            img.put(plane + i, ((c shr 8) and 0xFF).toByte())
            img.put(2 * plane + i, (c and 0xFF).toByte())
            // MI-GAN: 255 = keep, 0 = fill.
            msk.put(i, if ((mask[i].toInt() and 0xFF) > 0) 0.toByte() else 255.toByte())
        }
        img.rewind(); msk.rewind()
        OnnxTensor.createTensor(env, img, longArrayOf(1, 3, size.toLong(), size.toLong()), OnnxJavaType.UINT8).use { ti ->
            OnnxTensor.createTensor(env, msk, longArrayOf(1, 1, size.toLong(), size.toLong()), OnnxJavaType.UINT8).use { tm ->
                session.run(mapOf(nameOf("image", 0) to ti, nameOf("mask", 1) to tm)).use { r ->
                    val bb = (r.get(0) as OnnxTensor).byteBuffer
                    return IntArray(plane) { i ->
                        val rr = bb.get(i).toInt() and 0xFF
                        val gg = bb.get(plane + i).toInt() and 0xFF
                        val b = bb.get(2 * plane + i).toInt() and 0xFF
                        (0xFF shl 24) or (rr shl 16) or (gg shl 8) or b
                    }
                }
            }
        }
    }

    private fun nameOf(preferred: String, index: Int) =
        inputNames.firstOrNull { it.equals(preferred, ignoreCase = true) } ?: inputNames[index]

    /** Square around the mask with plenty of context (the model needs to see the surroundings). */
    private fun contextSquare(b: Rect, w: Int, h: Int): Rect {
        val longest = max(b.width(), b.height())
        var side = max((longest * 1.9f).roundToInt() + 64, 256)
        side = min(side, min(w, h).coerceAtLeast(1))
        val cx = b.centerX()
        val cy = b.centerY()
        val left = (cx - side / 2).coerceIn(0, max(0, w - side))
        val top = (cy - side / 2).coerceIn(0, max(0, h - side))
        // Wide masks on narrow images: fall back to a rectangle covering the mask.
        val r = Rect(left, top, min(w, left + side), min(h, top + side))
        if (!r.contains(b)) r.union(Rect(max(0, b.left - 32), max(0, b.top - 32), min(w, b.right + 32), min(h, b.bottom + 32)))
        return r
    }

    companion object {
        private const val SIZE = 512
    }
}

/** Finds the main subject(s) of a photo and returns a soft alpha mask in image size. */
class Segmenter(model: ToolModel, manager: ModelManager, threads: Int) : ToolEngine(model, manager, threads) {
    private val size = if (model.format == ToolFormat.U2NET) 320 else 1024

    fun segment(image: Bitmap): Bitmap {
        val scaled = Bitmap.createScaledBitmap(image, size, size, true)
        val plane = size * size
        val pixels = IntArray(plane)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== image) scaled.recycle()
        val (mean, std) = when (model.format) {
            ToolFormat.ISNET -> floatArrayOf(0.5f, 0.5f, 0.5f) to floatArrayOf(1f, 1f, 1f)
            else -> floatArrayOf(0.485f, 0.456f, 0.406f) to floatArrayOf(0.229f, 0.224f, 0.225f)
        }
        val input = floatBuffer(3 * plane)
        for (i in 0 until plane) {
            val c = pixels[i]
            input.put(i, (((c shr 16) and 0xFF) / 255f - mean[0]) / std[0])
            input.put(plane + i, (((c shr 8) and 0xFF) / 255f - mean[1]) / std[1])
            input.put(2 * plane + i, ((c and 0xFF) / 255f - mean[2]) / std[2])
        }
        input.rewind()
        val values = FloatArray(plane)
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
            session.run(mapOf(inputNames.first() to t)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                fb.get(values, 0, plane)
            }
        }
        when (model.format) {
            ToolFormat.BIREFNET -> for (i in values.indices) values[i] = 1f / (1f + exp(-values[i]))
            ToolFormat.U2NET -> {
                var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
                for (v in values) { lo = min(lo, v); hi = max(hi, v) }
                val range = (hi - lo).coerceAtLeast(1e-6f)
                for (i in values.indices) values[i] = (values[i] - lo) / range
            }
            else -> Unit
        }
        val small = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val row = small.rowBytes
        val bytes = ByteArray(row * size)
        for (y in 0 until size) for (x in 0 until size) {
            bytes[y * row + x] = (values[y * size + x].coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        }
        small.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        val full = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ALPHA_8)
        Canvas(full).drawBitmap(small, null, Rect(0, 0, image.width, image.height), filter)
        small.recycle()
        return full
    }
}

/** What to do with the background once the subject is known. */
enum class BackgroundEffect(val label: String) {
    BLUR("Unschärfe"),
    COLOR_POP("Farbakzent"),
    WHITE("Weiß"),
    CUTOUT("Freistellen"),
}

object BackgroundEffects {
    private val dstIn = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    /** Subject only, background transparent. */
    fun cutout(image: Bitmap, mask: Bitmap): Bitmap {
        val out = image.copy(Bitmap.Config.ARGB_8888, true)
        out.setHasAlpha(true)
        Canvas(out).drawBitmap(mask, 0f, 0f, dstIn)
        return out
    }

    /** [strength] 0..1 = gentle .. strong background blur. */
    fun apply(image: Bitmap, mask: Bitmap, effect: BackgroundEffect, strength: Float = 0.6f): Bitmap {
        if (effect == BackgroundEffect.CUTOUT) return cutout(image, mask)
        val background: Bitmap = when (effect) {
            BackgroundEffect.BLUR -> blurred(image, strength)
            BackgroundEffect.COLOR_POP -> Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(image, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) })
            }
            else -> Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.WHITE) }
        }
        val subject = cutout(image, mask)
        Canvas(background).drawBitmap(subject, 0f, 0f, null)
        subject.recycle()
        return background
    }

    /** Soft lens-like blur: downscale, box-blur the small image, scale back up. */
    private fun blurred(image: Bitmap, strength: Float): Bitmap {
        val longEdge = max(image.width, image.height)
        val target = (longEdge / (8f + 40f * strength.coerceIn(0f, 1f))).roundToInt().coerceAtLeast(16)
        val s = target.toFloat() / longEdge
        val sw = (image.width * s).roundToInt().coerceAtLeast(4)
        val sh = (image.height * s).roundToInt().coerceAtLeast(4)
        val small = Bitmap.createScaledBitmap(image, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        repeat(3) { boxBlur(px, sw, sh, 2) }
        small.setPixels(px, 0, sw, 0, 0, sw, sh)
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(small, null, Rect(0, 0, image.width, image.height), Paint(Paint.FILTER_BITMAP_FLAG))
        small.recycle()
        return out
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (line in 0 until lines) {
            for (i in 0 until len) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val c = if (horizontal) src[line * w + j] else src[j * w + line]
                    a += (c ushr 24); rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                val v = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
                if (horizontal) dst[line * w + i] = v else dst[i * w + line] = v
            }
        }
    }
}

/** Raw alpha values of an ALPHA_8 bitmap, row by row without padding. */
internal fun alphaBytes(bitmap: Bitmap): ByteArray {
    val row = bitmap.rowBytes
    val raw = ByteArray(row * bitmap.height)
    bitmap.copyPixelsToBuffer(ByteBuffer.wrap(raw))
    if (row == bitmap.width) return raw
    val out = ByteArray(bitmap.width * bitmap.height)
    for (y in 0 until bitmap.height) System.arraycopy(raw, y * row, out, y * bitmap.width, bitmap.width)
    return out
}

/** Bounding box of all non-transparent pixels of an ALPHA_8 mask, or null if it is empty. */
internal fun alphaBounds(mask: Bitmap): Rect? {
    val bytes = alphaBytes(mask)
    val w = mask.width
    var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
    for (y in 0 until mask.height) {
        val off = y * w
        for (x in 0 until w) {
            if (bytes[off + x].toInt() != 0) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                b = y
            }
        }
    }
    return if (r < 0) null else Rect(l, t, r + 1, b + 1)
}
