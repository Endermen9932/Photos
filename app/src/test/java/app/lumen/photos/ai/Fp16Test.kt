package app.lumen.photos.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class Fp16Test {
    @Test
    fun roundTripKeepsPrecision() {
        val rnd = Random(42)
        val values = FloatArray(2000) { (rnd.nextFloat() - 0.5f) * 0.4f } + floatArrayOf(0f, 1f, -1f, 0.5f, 65504f, 1e-5f)
        val decoded = Fp16.decode(Fp16.encode(values))
        for (i in values.indices) {
            val v = values[i]
            val tolerance = maxOf(kotlin.math.abs(v) * 1e-3f, 1e-6f)
            assertEquals("index $i", v, decoded[i], tolerance)
        }
    }

    @Test
    fun matchesJavaHalfConversion() {
        val rnd = Random(7)
        repeat(5000) {
            val f = (rnd.nextFloat() - 0.5f) * 10f
            assertEquals(java.lang.Float.floatToFloat16(f), Fp16.toHalf(f))
        }
    }
}
