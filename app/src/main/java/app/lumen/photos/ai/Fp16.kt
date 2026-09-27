package app.lumen.photos.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** IEEE 754 half precision helpers used to halve the on-disk size of embeddings. */
object Fp16 {
    /** Float to half with round-to-nearest-even (same result as Java 20's Float.floatToFloat16). */
    fun toHalf(f: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val abs = bits and 0x7fffffff
        if (abs >= 0x7f800000) { // Inf / NaN
            val nan = if (abs > 0x7f800000) 0x200 or ((abs ushr 13) and 0x3ff) else 0
            return (sign or 0x7c00 or nan).toShort()
        }
        if (abs >= 0x477ff000) return (sign or 0x7c00).toShort() // overflow -> Inf
        if (abs < 0x38800000) { // half subnormal or zero
            if (abs < 0x33000000) return sign.toShort()
            val e = abs ushr 23
            val mant = (abs and 0x7fffff) or 0x800000
            val shift = 126 - e
            var r = mant ushr shift
            val rem = mant and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            if (rem > halfway || (rem == halfway && (r and 1) == 1)) r++
            return (sign or r).toShort()
        }
        var h = (abs - 0x38000000) ushr 13
        val rem = abs and 0x1fff
        if (rem > 0x1000 || (rem == 0x1000 && (h and 1) == 1)) h++
        return (sign or h).toShort()
    }

    fun toFloat(h: Short): Float {
        val bits = h.toInt() and 0xffff
        val sign = (bits and 0x8000) shl 16
        var exp = (bits ushr 10) and 0x1f
        var mant = bits and 0x3ff
        if (exp == 0) {
            if (mant == 0) return java.lang.Float.intBitsToFloat(sign)
            while (mant and 0x400 == 0) {
                mant = mant shl 1
                exp--
            }
            exp++
            mant = mant and 0x3ff
        } else if (exp == 31) {
            return java.lang.Float.intBitsToFloat(sign or 0x7f800000 or (mant shl 13))
        }
        return java.lang.Float.intBitsToFloat(sign or ((exp + 112) shl 23) or (mant shl 13))
    }

    fun encode(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putShort(toHalf(x))
        return buf.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 2) { toFloat(buf.getShort()) }
    }
}
