package app.lumen.photos.edit

import androidx.compose.runtime.Immutable
import app.lumen.photos.ai.DownloadableModel
import app.lumen.photos.ai.ModelFile

/** The image tools that run on their own AI models. */
enum class ToolKind(val title: String, val subtitle: String) {
    UPSCALE("Hochskalieren", "Mehr Pixel und schärfere Details – ideal für kleine, alte oder stark komprimierte Fotos."),
    INPAINT("Objekte entfernen", "Radierer im KI-Editor: Personen, Kabel oder Flecken wegwischen, die KI füllt die Lücke."),
    SEGMENT("Hintergrund erkennen", "Freistellen, Hintergrund unscharf, schwarz-weiß oder weiß im KI-Editor."),
}

/** How a model expects its input and delivers its output (see [ToolEngines]). */
enum class ToolFormat {
    /** Real-ESRGAN with a fixed tile size, RGB 0..1 in and out, 4× output. */
    ESRGAN_TILE,

    /** Swin2SR with dynamic size (multiple of 8), RGB 0..1 in and out, 4× output. */
    SWIN2SR,

    /** MI-GAN pipeline: uint8 RGB + uint8 mask (0 = fill), any size, uint8 out. */
    MIGAN,

    /** LaMa: 512×512 RGB 0..1 + mask (1 = fill), RGB 0..255 out. */
    LAMA,

    /** U²-Net: 320×320 ImageNet-normalised, first output is the mask (0..1, min-max normalised). */
    U2NET,

    /** IS-Net: 1024×1024, (x − 0.5) / 1, mask 0..1. */
    ISNET,

    /** BiRefNet: 1024×1024 ImageNet-normalised, mask logits. */
    BIREFNET,
}

/** One downloadable model for an image tool. Everything runs offline after the download. */
@Immutable
data class ToolModel(
    override val id: String,
    val kind: ToolKind,
    val tier: String,
    val name: String,
    val description: String,
    override val repo: String,
    val file: ModelFile,
    val format: ToolFormat,
    /** 1..5 for the UI. */
    val speed: Int,
    val accuracy: Int,
    /**
     * Rough time on a Pixel 10 Pro: for upscalers seconds per megapixel of the input image,
     * otherwise seconds per run.
     */
    val seconds: Float,
    /** Input tile edge for upscalers (fixed by the model for Real-ESRGAN). */
    val tile: Int = 0,
) : DownloadableModel {
    override val files: List<ModelFile> get() = listOf(file)

    override fun equals(other: Any?) = other is ToolModel && other.id == id
    override fun hashCode() = id.hashCode()
}

object ToolModelCatalog {
    val models: List<ToolModel> = listOf(
        // ---------------------------------------------------------------- upscaling (all 4×)
        ToolModel(
            id = "up-realesr-general-v3",
            kind = ToolKind.UPSCALE,
            tier = "Schnell",
            name = "Real-ESRGAN General v3",
            description = "Winzig und flott: schärft Kanten und entfernt JPEG-Artefakte in Sekunden.",
            repo = "tamnvcc/Real-ESRGAN-General-x4v3_float",
            file = ModelFile("onnx/model.onnx", 4_876_654, "model.onnx"),
            format = ToolFormat.ESRGAN_TILE,
            speed = 5, accuracy = 3, seconds = 12f, tile = 128,
        ),
        ToolModel(
            id = "up-swin2sr-realworld",
            kind = ToolKind.UPSCALE,
            tier = "Natürlich",
            name = "Swin2SR Real-World",
            description = "Transformer-Modell mit sehr natürlichem Ergebnis ohne überzeichnete Details – gut für Gesichter und Haut.",
            repo = "Xenova/swin2SR-realworld-sr-x4-64-bsrgan-psnr",
            file = ModelFile("onnx/model.onnx", 52_772_645, "model.onnx"),
            format = ToolFormat.SWIN2SR,
            speed = 1, accuracy = 4, seconds = 220f, tile = 128,
        ),
        ToolModel(
            id = "up-realesrgan-x4plus",
            kind = ToolKind.UPSCALE,
            tier = "Sehr gut",
            name = "Real-ESRGAN x4plus",
            description = "Der Klassiker für Fotos: rekonstruiert feine Texturen wie Haare, Blätter und Stoff am stärksten.",
            repo = "imgdesignart/realesrgan-x4-onnx",
            file = ModelFile("onnx/model.onnx", 67_051_787, "model.onnx"),
            format = ToolFormat.ESRGAN_TILE,
            speed = 2, accuracy = 5, seconds = 200f, tile = 64,
        ),

        // ---------------------------------------------------------------- object removal
        ToolModel(
            id = "inpaint-migan",
            kind = ToolKind.INPAINT,
            tier = "Schnell",
            name = "MI-GAN",
            description = "Sehr schnell, gut für kleine Objekte vor ruhigem Hintergrund.",
            repo = "anyisalin/migan-onnx",
            file = ModelFile("onnx/migan_pipeline.onnx", 28_079_181, "model.onnx"),
            format = ToolFormat.MIGAN,
            speed = 5, accuracy = 3, seconds = 1f,
        ),
        ToolModel(
            id = "inpaint-lama",
            kind = ToolKind.INPAINT,
            tier = "Sehr gut",
            name = "LaMa",
            description = "Füllt auch große Flächen glaubwürdig und setzt Muster, Kanten und Strukturen fort.",
            repo = "opencv/inpainting_lama",
            file = ModelFile("inpainting_lama_2025jan.onnx", 92_591_623, "model.onnx"),
            format = ToolFormat.LAMA,
            speed = 3, accuracy = 5, seconds = 3f,
        ),

        // ---------------------------------------------------------------- background
        ToolModel(
            id = "seg-u2netp",
            kind = ToolKind.SEGMENT,
            tier = "Schnell",
            name = "U²-Net (klein)",
            description = "Winzig und blitzschnell. Kanten sind weicher, Haare werden nicht einzeln erkannt.",
            repo = "BritishWerewolf/U-2-Netp",
            file = ModelFile("onnx/model.onnx", 4_574_861, "model.onnx"),
            format = ToolFormat.U2NET,
            speed = 5, accuracy = 2, seconds = 0.4f,
        ),
        ToolModel(
            id = "seg-isnet",
            kind = ToolKind.SEGMENT,
            tier = "Ausgewogen",
            name = "IS-Net",
            description = "Saubere Kanten bei Personen, Tieren und Gegenständen – der empfohlene Standard.",
            repo = "imgly/isnet-general-onnx",
            file = ModelFile("onnx/model_fp16.onnx", 88_152_708, "model.onnx"),
            format = ToolFormat.ISNET,
            speed = 3, accuracy = 4, seconds = 2f,
        ),
        ToolModel(
            id = "seg-birefnet",
            kind = ToolKind.SEGMENT,
            tier = "Sehr gut",
            name = "BiRefNet",
            description = "Das genaueste Modell: trennt einzelne Haarsträhnen, Fell und feine Details.",
            repo = "onnx-community/BiRefNet_lite-ONNX",
            file = ModelFile("onnx/model_fp16.onnx", 114_538_221, "model.onnx"),
            format = ToolFormat.BIREFNET,
            speed = 1, accuracy = 5, seconds = 20f,
        ),
    )

    fun byId(id: String?): ToolModel? = models.firstOrNull { it.id == id }
    fun of(kind: ToolKind): List<ToolModel> = models.filter { it.kind == kind }
}
