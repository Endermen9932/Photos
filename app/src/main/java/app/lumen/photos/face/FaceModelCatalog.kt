package app.lumen.photos.face

import androidx.compose.runtime.Immutable
import app.lumen.photos.ai.DownloadableModel
import app.lumen.photos.ai.ModelFile

/**
 * Face detection (SCRFD) + face recognition (ArcFace) model pairs from InsightFace, in the ONNX
 * packaging used by Immich. Everything runs offline after a single download.
 */
@Immutable
data class FaceModel(
    override val id: String,
    val tier: String,
    val name: String,
    val description: String,
    override val repo: String,
    val detection: ModelFile,
    val recognition: ModelFile,
    /** Cosine similarity above which a face is added to a person automatically. */
    val assignThreshold: Float,
    val speed: Int,
    val accuracy: Int,
    val msPerImage: Int,
) : DownloadableModel {
    override val files: List<ModelFile> get() = listOf(detection, recognition)

    override fun equals(other: Any?) = other is FaceModel && other.id == id
    override fun hashCode() = id.hashCode()
}

object FaceModelCatalog {
    val models = listOf(
        FaceModel(
            id = "face-buffalo-s",
            tier = "Schnell",
            name = "SCRFD 500M + MobileFaceNet",
            description = "Winzig und flott. Gut für große Galerien, erkennt kleine oder seitliche Gesichter seltener.",
            repo = "immich-app/buffalo_s",
            detection = ModelFile("detection/model.onnx", 2_524_817, "detection.onnx"),
            recognition = ModelFile("recognition/model.onnx", 13_616_099, "recognition.onnx"),
            assignThreshold = 0.50f,
            speed = 5, accuracy = 3, msPerImage = 60,
        ),
        FaceModel(
            id = "face-buffalo-l",
            tier = "Ausgewogen",
            name = "SCRFD 10G + ArcFace R50",
            description = "Starker Detektor und genaue Wiedererkennung – der empfohlene Standard.",
            repo = "immich-app/buffalo_l",
            detection = ModelFile("detection/model.onnx", 16_923_827, "detection.onnx"),
            recognition = ModelFile("recognition/model.onnx", 174_383_860, "recognition.onnx"),
            assignThreshold = 0.46f,
            speed = 3, accuracy = 4, msPerImage = 260,
        ),
        FaceModel(
            id = "face-antelopev2",
            tier = "Sehr gut",
            name = "SCRFD 10G + ArcFace R100 (Glint360K)",
            description = "Das genaueste Modell: erkennt Personen auch über Jahre, mit Brille oder schlechtem Licht.",
            repo = "immich-app/antelopev2",
            detection = ModelFile("detection/model.onnx", 16_923_827, "detection.onnx"),
            recognition = ModelFile("recognition/model.onnx", 260_665_334, "recognition.onnx"),
            assignThreshold = 0.42f,
            speed = 2, accuracy = 5, msPerImage = 520,
        ),
    )

    fun byId(id: String?): FaceModel? = models.firstOrNull { it.id == id }
}
