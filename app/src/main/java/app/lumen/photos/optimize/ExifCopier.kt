package app.lumen.photos.optimize

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Carries metadata (capture date, camera, GPS, …) from an original photo to its optimised version.
 *
 * For JPEG output the EXIF block is spliced in as a separate APP1 segment right after SOI instead of
 * letting ExifInterface rewrite the whole file. That keeps everything Bitmap.compress() produced
 * untouched – in particular the Ultra HDR gain map (XMP + MPF segments and the appended secondary
 * image), whose offsets are relative to their own segments and therefore stay valid.
 */
object ExifCopier {

    /** Tags that describe the pixel data of the original and must not be copied. */
    private val EXCLUDED = setOf(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_IMAGE_WIDTH,
        ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_PIXEL_X_DIMENSION,
        ExifInterface.TAG_PIXEL_Y_DIMENSION,
        ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
        ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
        ExifInterface.TAG_THUMBNAIL_ORIENTATION,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
        ExifInterface.TAG_STRIP_OFFSETS,
        ExifInterface.TAG_STRIP_BYTE_COUNTS,
        ExifInterface.TAG_ROWS_PER_STRIP,
        ExifInterface.TAG_COMPRESSION,
        ExifInterface.TAG_BITS_PER_SAMPLE,
        ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
        ExifInterface.TAG_SAMPLES_PER_PIXEL,
        ExifInterface.TAG_PLANAR_CONFIGURATION,
        ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING,
        ExifInterface.TAG_Y_CB_CR_POSITIONING,
        ExifInterface.TAG_Y_CB_CR_COEFFICIENTS,
        ExifInterface.TAG_NEW_SUBFILE_TYPE,
        ExifInterface.TAG_SUBFILE_TYPE,
        ExifInterface.TAG_XMP,
        ExifInterface.TAG_MAKER_NOTE,
        ExifInterface.TAG_DNG_VERSION,
        ExifInterface.TAG_DEFAULT_CROP_SIZE,
        ExifInterface.TAG_ORF_THUMBNAIL_IMAGE,
        ExifInterface.TAG_ORF_PREVIEW_IMAGE_START,
        ExifInterface.TAG_ORF_PREVIEW_IMAGE_LENGTH,
        ExifInterface.TAG_ORF_ASPECT_FRAME,
        ExifInterface.TAG_RW2_SENSOR_BOTTOM_BORDER,
        ExifInterface.TAG_RW2_SENSOR_LEFT_BORDER,
        ExifInterface.TAG_RW2_SENSOR_RIGHT_BORDER,
        ExifInterface.TAG_RW2_SENSOR_TOP_BORDER,
        ExifInterface.TAG_RW2_ISO,
        ExifInterface.TAG_RW2_JPG_FROM_RAW,
    )

    /** All public TAG_* constants of ExifInterface (kept by R8, see proguard-rules.pro). */
    private val ALL_TAGS: List<String> by lazy {
        ExifInterface::class.java.fields
            .filter { it.name.startsWith("TAG_") && it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .mapNotNull { runCatching { it.get(null) as String }.getOrNull() }
            .distinct()
            .filter { it !in EXCLUDED }
    }

    fun readAttributes(input: InputStream): Map<String, String> {
        val exif = ExifInterface(input)
        val result = LinkedHashMap<String, String>()
        for (tag in ALL_TAGS) {
            exif.getAttribute(tag)?.let { result[tag] = it }
        }
        return result
    }

    private fun apply(exif: ExifInterface, attributes: Map<String, String>, width: Int, height: Int) {
        for ((tag, value) in attributes) runCatching { exif.setAttribute(tag, value) }
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Lumen Photos (optimiert)")
    }

    /** Returns [jpeg] with an EXIF APP1 segment containing [attributes] inserted after SOI. */
    fun spliceIntoJpeg(jpeg: ByteArray, attributes: Map<String, String>, width: Int, height: Int, tempDir: File): ByteArray {
        if (attributes.isEmpty()) return jpeg
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg

        // Let ExifInterface build a valid EXIF segment on a tiny carrier JPEG.
        val carrier = File.createTempFile("exif", ".jpg", tempDir)
        try {
            val tiny = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            carrier.outputStream().use { tiny.compress(Bitmap.CompressFormat.JPEG, 50, it) }
            tiny.recycle()
            val exif = ExifInterface(carrier)
            apply(exif, attributes, width, height)
            exif.saveAttributes()
            val segment = findExifSegment(carrier.readBytes()) ?: return jpeg

            // Drop any EXIF segment the encoder may have written itself.
            val body = removeExifSegments(jpeg)
            val out = ByteArrayOutputStream(body.size + segment.size)
            out.write(body, 0, 2) // SOI
            out.write(segment)
            out.write(body, 2, body.size - 2)
            return out.toByteArray()
        } finally {
            carrier.delete()
        }
    }

    /** Writes metadata into a non-JPEG file (e.g. WebP) using ExifInterface directly. */
    fun writeToFile(file: File, attributes: Map<String, String>, width: Int, height: Int) {
        if (attributes.isEmpty()) return
        runCatching {
            val exif = ExifInterface(file)
            apply(exif, attributes, width, height)
            exif.saveAttributes()
        }
    }

    private fun isExif(data: ByteArray, start: Int): Boolean =
        start + 10 <= data.size &&
            data[start + 4] == 'E'.code.toByte() && data[start + 5] == 'x'.code.toByte() &&
            data[start + 6] == 'i'.code.toByte() && data[start + 7] == 'f'.code.toByte() &&
            data[start + 8] == 0.toByte() && data[start + 9] == 0.toByte()

    /** Iterates over the marker segments before SOS; returns (offset, totalLength, marker). */
    private inline fun forEachSegment(data: ByteArray, block: (Int, Int, Int) -> Unit) {
        var i = 2
        while (i + 4 <= data.size) {
            if (data[i] != 0xFF.toByte()) return
            val marker = data[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return // start of scan / end of image
            val len = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            block(i, len + 2, marker)
            i += len + 2
        }
    }

    private fun findExifSegment(data: ByteArray): ByteArray? {
        forEachSegment(data) { offset, length, marker ->
            if (marker == 0xE1 && isExif(data, offset)) return data.copyOfRange(offset, offset + length)
        }
        return null
    }

    private fun removeExifSegments(data: ByteArray): ByteArray {
        val ranges = ArrayList<IntRange>()
        forEachSegment(data) { offset, length, marker ->
            if (marker == 0xE1 && isExif(data, offset)) ranges += offset until offset + length
        }
        if (ranges.isEmpty()) return data
        val out = ByteArrayOutputStream(data.size)
        var pos = 0
        for (r in ranges) {
            out.write(data, pos, r.first - pos)
            pos = r.last + 1
        }
        out.write(data, pos, data.size - pos)
        return out.toByteArray()
    }
}
