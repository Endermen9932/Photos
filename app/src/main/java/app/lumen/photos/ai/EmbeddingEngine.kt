package app.lumen.photos.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.lumen.photos.ai.tokenizer.BpeTokenizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Runs one [AiModel] with ONNX Runtime on the CPU. Vision and text towers are loaded lazily and
 * independently so indexing never keeps the text model in memory (and vice versa).
 */
class EmbeddingEngine(
    val model: AiModel,
    private val manager: ModelManager,
    private val threads: Int,
    private val useXnnpack: Boolean,
) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private var vision: OrtSession? = null
    private var text: OrtSession? = null
    private var tokenizer: BpeTokenizer? = null
    private val lock = Any()

    private fun options(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(threads.coerceIn(1, 8))
        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        setMemoryPatternOptimization(true)
        if (useXnnpack) {
            runCatching { addXnnpack(mapOf("intra_op_num_threads" to threads.coerceIn(1, 8).toString())) }
        }
    }

    private fun visionSession(): OrtSession = synchronized(lock) {
        vision ?: env.createSession(manager.file(model, model.visionFile).absolutePath, options()).also { vision = it }
    }

    private fun textSession(): OrtSession = synchronized(lock) {
        text ?: env.createSession(manager.file(model, model.textFile).absolutePath, options()).also { text = it }
    }

    private fun tokenizer(): BpeTokenizer = synchronized(lock) {
        tokenizer ?: BpeTokenizer.load(manager.file(model, model.tokenizerFile), manager.tokenizerCache(model))
            .also { tokenizer = it }
    }

    /** Pre-loads the text tower so the first search is instant. */
    fun warmUpText() {
        tokenizer()
        textSession()
    }

    fun releaseVision() = synchronized(lock) {
        vision?.close()
        vision = null
    }

    fun releaseText() = synchronized(lock) {
        text?.close()
        text = null
    }

    /** Pixel buffer reused between calls (indexing is sequential). */
    private var pixelBuffer: FloatBuffer? = null
    private var pixelInts: IntArray? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Scales/crops a decoded bitmap to the model's input resolution. */
    fun prepare(source: Bitmap): Bitmap {
        val size = model.imageSize
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val src = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        when (model.resizeMode) {
            ResizeMode.SQUASH -> canvas.drawBitmap(src, null, RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
            ResizeMode.SHORTEST_EDGE_CENTER_CROP -> {
                val side = minOf(w, h)
                val left = ((w - side) / 2f).toInt()
                val top = ((h - side) / 2f).toInt()
                canvas.drawBitmap(
                    src,
                    Rect(left, top, left + side.toInt(), top + side.toInt()),
                    RectF(0f, 0f, size.toFloat(), size.toFloat()),
                    paint
                )
            }
        }
        if (src !== source) src.recycle()
        return out
    }

    /** Returns an L2-normalised image embedding. */
    fun embedImage(bitmap: Bitmap): FloatArray {
        val size = model.imageSize
        val prepared = if (bitmap.width == size && bitmap.height == size && bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            prepare(bitmap)
        }
        val plane = size * size
        val ints = pixelInts?.takeIf { it.size == plane } ?: IntArray(plane).also { pixelInts = it }
        prepared.getPixels(ints, 0, size, 0, 0, size, size)
        if (prepared !== bitmap) prepared.recycle()

        val buffer = pixelBuffer?.takeIf { it.capacity() == plane * 3 }
            ?: ByteBuffer.allocateDirect(plane * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { pixelBuffer = it }
        val mean = model.mean
        val std = model.std
        val sR = 1f / (255f * std[0]); val sG = 1f / (255f * std[1]); val sB = 1f / (255f * std[2])
        val oR = mean[0] / std[0]; val oG = mean[1] / std[1]; val oB = mean[2] / std[2]
        for (i in 0 until plane) {
            val c = ints[i]
            buffer.put(i, ((c shr 16) and 0xFF) * sR - oR)
            buffer.put(plane + i, ((c shr 8) and 0xFF) * sG - oG)
            buffer.put(2 * plane + i, (c and 0xFF) * sB - oB)
        }
        buffer.rewind()
        val session = visionSession()
        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            val outputName = pickOutput(session.outputNames, "image_embeds")
            session.run(mapOf(inputName to tensor), setOf(outputName)).use { result ->
                val out = result.get(outputName).get() as OnnxTensor
                return normalize(readFloats(out))
            }
        }
    }

    /** Returns an L2-normalised text embedding. */
    fun embedText(query: String): FloatArray {
        val tok = tokenizer()
        val ids = tok.encode(query.trim(), BpeTokenizer.Config(maxLength = model.maxTextLength, padId = 0, lowercase = true))
        val session = textSession()
        val inputName = session.inputNames.firstOrNull { it.contains("input_ids") } ?: session.inputNames.first()
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { tensor ->
            val inputs = HashMap<String, OnnxTensor>()
            inputs[inputName] = tensor
            var mask: OnnxTensor? = null
            if (session.inputNames.contains("attention_mask")) {
                val m = LongArray(ids.size) { 1 }
                mask = OnnxTensor.createTensor(env, LongBuffer.wrap(m), longArrayOf(1, ids.size.toLong()))
                inputs["attention_mask"] = mask
            }
            try {
                val outputName = pickOutput(session.outputNames, "text_embeds")
                session.run(inputs, setOf(outputName)).use { result ->
                    val out = result.get(outputName).get() as OnnxTensor
                    return normalize(readFloats(out))
                }
            } finally {
                mask?.close()
            }
        }
    }

    private fun pickOutput(names: Set<String>, preferred: String): String =
        names.firstOrNull { it == preferred } ?: names.firstOrNull { it == "pooler_output" }
        ?: names.firstOrNull { it.endsWith("embeds") } ?: names.last()

    private fun readFloats(tensor: OnnxTensor): FloatArray {
        val fb = tensor.floatBuffer
        val arr = FloatArray(fb.remaining())
        fb.get(arr)
        return arr
    }

    override fun close() = synchronized(lock) {
        vision?.close(); vision = null
        text?.close(); text = null
    }

    companion object {
        fun normalize(v: FloatArray): FloatArray {
            var sum = 0.0
            for (x in v) sum += x * x
            val inv = if (sum > 0) (1.0 / sqrt(sum)).toFloat() else 0f
            for (i in v.indices) v[i] *= inv
            return v
        }
    }
}
