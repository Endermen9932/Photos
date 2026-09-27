package app.lumen.photos.ai.tokenizer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

/**
 * Compares the Kotlin tokenizer against reference ids produced by the Hugging Face `tokenizers`
 * library. The (large) tokenizer.json files are not checked in; point the environment variable
 * `LUMEN_TOKENIZER_DIR` to a folder with `clip/tokenizer.json` and `sp/tokenizer.json` to run it.
 */
class BpeTokenizerTest {

    private val expected by lazy {
        val text = javaClass.classLoader!!.getResource("tokenizer_expected.json")!!.readText()
        val reader = JsonStreamReader(StringReader(text))
        val result = HashMap<String, List<Pair<String, IntArray>>>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            val cases = ArrayList<Pair<String, IntArray>>()
            reader.beginArray()
            while (reader.hasNext()) {
                var t = ""
                val ids = ArrayList<Int>()
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "text" -> t = reader.nextString()
                        "ids" -> {
                            reader.beginArray()
                            while (reader.hasNext()) ids.add(reader.nextInt())
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                cases.add(t to ids.toIntArray())
            }
            reader.endArray()
            result[name] = cases
        }
        reader.endObject()
        result
    }

    private fun tokenizerFile(kind: String): File? {
        val dir = System.getenv("LUMEN_TOKENIZER_DIR") ?: return null
        return File(dir, "$kind/tokenizer.json").takeIf { it.exists() }
    }

    @Test
    fun jsonReaderHandlesEscapesAndNesting() {
        val r = JsonStreamReader(StringReader("""{"a":"x\"yü","b":[1,2.5,{"c":null}],"d":true}"""))
        r.beginObject()
        assertEquals("a", r.nextName())
        assertEquals("x\"yü", r.nextString())
        assertEquals("b", r.nextName())
        r.skipValue()
        assertEquals("d", r.nextName())
        assertEquals(true, r.nextBoolean())
        r.endObject()
    }

    @Test
    fun clipMatchesReference() = check("clip", BpeTokenizer.Style.CLIP)

    @Test
    fun sentencePieceMatchesReference() = check("sp", BpeTokenizer.Style.SENTENCEPIECE)

    @Test
    fun binaryCacheRoundTrip() {
        val file = tokenizerFile("clip")
        assumeTrue(file != null)
        val tok = BpeTokenizer.parse(file!!)
        val cache = File.createTempFile("tok", ".bin")
        tok.writeCache(cache)
        val restored = BpeTokenizer.readCache(cache)
        for ((text, ids) in expected.getValue("clip")) assertArrayEquals(text, ids, restored.tokenize(text))
        cache.delete()
    }

    private fun check(kind: String, style: BpeTokenizer.Style) {
        val file = tokenizerFile(kind)
        assumeTrue("tokenizer.json for $kind not available", file != null)
        val tok = BpeTokenizer.parse(file!!)
        assertEquals(style, tok.style)
        for ((text, ids) in expected.getValue(kind)) {
            assertArrayEquals("Mismatch for '$text'", ids, tok.tokenize(text))
        }
    }
}
