package app.lumen.photos.ai

import androidx.compose.runtime.Immutable

enum class ModelFamily { CLIP, SIGLIP }

enum class ResizeMode {
    /** Resize the shortest edge to the input size, then center crop (CLIP). */
    SHORTEST_EDGE_CENTER_CROP,

    /** Squash the whole image to a square (SigLIP). */
    SQUASH,
}

@Immutable
data class ModelFile(val path: String, val sizeBytes: Long)

/**
 * Description of one on-device image/text embedding model. All models run fully offline through
 * ONNX Runtime; only the one-time download of the weights needs a network connection.
 */
@Immutable
data class AiModel(
    val id: String,
    val name: String,
    val tier: String,
    val description: String,
    val repo: String,
    val visionFile: ModelFile,
    val visionDataFile: ModelFile? = null,
    val textFile: ModelFile,
    val tokenizerFile: ModelFile,
    val family: ModelFamily,
    val imageSize: Int,
    val resizeMode: ResizeMode,
    val mean: FloatArray,
    val std: FloatArray,
    val maxTextLength: Int,
    val multilingual: Boolean,
    val embeddingDim: Int,
    /** 1..5 for the UI. */
    val speed: Int,
    val accuracy: Int,
    /** Rough estimate on a Pixel 10 Pro (Tensor G5, 6 threads). */
    val msPerImage: Int,
    val ramMb: Int,
) {
    val files: List<ModelFile> get() = listOfNotNull(visionFile, visionDataFile, textFile, tokenizerFile)
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    fun url(file: ModelFile) = "https://huggingface.co/$repo/resolve/main/${file.path}"

    override fun equals(other: Any?) = other is AiModel && other.id == id
    override fun hashCode() = id.hashCode()
}

object ModelCatalog {
    private val CLIP_ZERO = floatArrayOf(0f, 0f, 0f)
    private val CLIP_ONE = floatArrayOf(1f, 1f, 1f)
    private val HALF = floatArrayOf(0.5f, 0.5f, 0.5f)
    private const val SIGLIP_TOKENIZER_SIZE = 34_363_039L

    val models: List<AiModel> = listOf(
        AiModel(
            id = "mobileclip-s0",
            name = "MobileCLIP S0",
            tier = "Blitz",
            description = "Winzig und extrem schnell. Versteht nur englische Suchbegriffe.",
            repo = "Xenova/mobileclip_s0",
            visionFile = ModelFile("onnx/vision_model.onnx", 45_543_630),
            textFile = ModelFile("onnx/text_model_quantized.onnx", 42_799_238),
            tokenizerFile = ModelFile("tokenizer.json", 2_224_081),
            family = ModelFamily.CLIP,
            imageSize = 256,
            resizeMode = ResizeMode.SHORTEST_EDGE_CENTER_CROP,
            mean = CLIP_ZERO,
            std = CLIP_ONE,
            maxTextLength = 77,
            multilingual = false,
            embeddingDim = 512,
            speed = 5, accuracy = 2, msPerImage = 25, ramMb = 350,
        ),
        AiModel(
            id = "siglip2-b32-256",
            name = "SigLIP 2 Base/32",
            tier = "Schnell",
            description = "Schnelle Indexierung, versteht Deutsch und über 100 weitere Sprachen.",
            repo = "onnx-community/siglip2-base-patch32-256-ONNX",
            visionFile = ModelFile("onnx/vision_model_fp16.onnx", 189_374_263),
            textFile = ModelFile("onnx/text_model_q4f16.onnx", 442_779_433),
            tokenizerFile = ModelFile("tokenizer.json", SIGLIP_TOKENIZER_SIZE),
            family = ModelFamily.SIGLIP,
            imageSize = 256,
            resizeMode = ResizeMode.SQUASH,
            mean = HALF, std = HALF,
            maxTextLength = 64,
            multilingual = true,
            embeddingDim = 768,
            speed = 4, accuracy = 3, msPerImage = 70, ramMb = 1400,
        ),
        AiModel(
            id = "siglip2-b16-256",
            name = "SigLIP 2 Base/16",
            tier = "Ausgewogen",
            description = "Der beste Kompromiss aus Tempo und Genauigkeit. Mehrsprachig.",
            repo = "onnx-community/siglip2-base-patch16-256-ONNX",
            visionFile = ModelFile("onnx/vision_model_fp16.onnx", 186_131_676),
            textFile = ModelFile("onnx/text_model_q4f16.onnx", 442_779_433),
            tokenizerFile = ModelFile("tokenizer.json", SIGLIP_TOKENIZER_SIZE),
            family = ModelFamily.SIGLIP,
            imageSize = 256,
            resizeMode = ResizeMode.SQUASH,
            mean = HALF, std = HALF,
            maxTextLength = 64,
            multilingual = true,
            embeddingDim = 768,
            speed = 3, accuracy = 4, msPerImage = 220, ramMb = 1500,
        ),
        AiModel(
            id = "siglip2-l16-384",
            name = "SigLIP 2 Large/16 @384",
            tier = "Präzise",
            description = "Hohe Auflösung und großes Modell: erkennt auch kleine Details und Text im Bild.",
            repo = "onnx-community/siglip2-large-patch16-384-ONNX",
            visionFile = ModelFile("onnx/vision_model_fp16.onnx", 633_088_421),
            textFile = ModelFile("onnx/text_model_q4f16.onnx", 697_558_347),
            tokenizerFile = ModelFile("tokenizer.json", SIGLIP_TOKENIZER_SIZE),
            family = ModelFamily.SIGLIP,
            imageSize = 384,
            resizeMode = ResizeMode.SQUASH,
            mean = HALF, std = HALF,
            maxTextLength = 64,
            multilingual = true,
            embeddingDim = 1024,
            speed = 2, accuracy = 4, msPerImage = 1400, ramMb = 3200,
        ),
        AiModel(
            id = "siglip2-so400m-384",
            name = "SigLIP 2 So400m/14 @384",
            tier = "Ultra",
            description = "Formoptimiertes 400M-Modell. Sehr genaue Suche, braucht viel Zeit beim Indexieren.",
            repo = "onnx-community/siglip2-so400m-patch14-384-ONNX",
            visionFile = ModelFile("onnx/vision_model_fp16.onnx", 857_034_174),
            textFile = ModelFile("onnx/text_model_q4f16.onnx", 825_525_093),
            tokenizerFile = ModelFile("tokenizer.json", SIGLIP_TOKENIZER_SIZE),
            family = ModelFamily.SIGLIP,
            imageSize = 384,
            resizeMode = ResizeMode.SQUASH,
            mean = HALF, std = HALF,
            maxTextLength = 64,
            multilingual = true,
            embeddingDim = 1152,
            speed = 1, accuracy = 5, msPerImage = 2600, ramMb = 4200,
        ),
        AiModel(
            id = "siglip2-giant-384",
            name = "SigLIP 2 Giant/16 @384",
            tier = "Maximum",
            description = "Über 1 Mrd. Parameter – die genaueste Suche. Nutzt die 16 GB RAM des Pixel 10 Pro aus, " +
                "am besten über Nacht beim Laden indexieren.",
            repo = "onnx-community/siglip2-giant-opt-patch16-384-ONNX",
            visionFile = ModelFile("onnx/vision_model_fp16.onnx", 903_823),
            visionDataFile = ModelFile("onnx/vision_model_fp16.onnx_data", 2_327_319_552),
            textFile = ModelFile("onnx/text_model_q4f16.onnx", 826_410_597),
            tokenizerFile = ModelFile("tokenizer.json", SIGLIP_TOKENIZER_SIZE),
            family = ModelFamily.SIGLIP,
            imageSize = 384,
            resizeMode = ResizeMode.SQUASH,
            mean = HALF, std = HALF,
            maxTextLength = 64,
            multilingual = true,
            embeddingDim = 1536,
            speed = 1, accuracy = 5, msPerImage = 6500, ramMb = 7500,
        ),
    )

    fun byId(id: String?): AiModel? = models.firstOrNull { it.id == id }

    val default: AiModel get() = models[2]
}
