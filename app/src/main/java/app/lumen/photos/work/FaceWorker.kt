package app.lumen.photos.work

import android.content.Context
import android.util.Size
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lumen.photos.LumenApp
import app.lumen.photos.ai.Fp16
import app.lumen.photos.data.db.FaceEntity
import app.lumen.photos.data.db.FaceScanEntity
import app.lumen.photos.face.FaceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finds and recognises faces in all photos, then groups them into persons. Resumable. */
class FaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as LumenApp).container
        return WorkLocks.hold(applicationContext, "faces", c.settings.current.keepScreenOnDuringWork) { run() }
    }

    private suspend fun run(): Result = withContext(Dispatchers.Default) {
        val c = (applicationContext as LumenApp).container
        val model = c.faces.activeModel.value ?: return@withContext Result.success()
        if (!c.models.isInstalled(model)) return@withContext Result.failure()
        val dao = c.db.faces()

        c.media.reload()
        val photos = c.media.media.value.filter { it.isImage }
        val known = dao.scanKeys(model.id).associate { it.mediaId to it.dateModified }
        val present = photos.mapTo(HashSet()) { it.id }
        val gone = known.keys.filter { it !in present }
        if (gone.isNotEmpty()) {
            gone.chunked(500).forEach {
                dao.deleteFacesOfMedia(model.id, it)
                dao.deleteScans(model.id, it)
            }
        }
        photos.filter { known[it.id] == -1L }.forEach { dao.acceptReplaced(it.id, it.dateModified) }
        val todo = photos.filter { val k = known[it.id]; k != it.dateModified && k != -1L }
        if (todo.isEmpty()) {
            c.faces.assignUnassigned(model)
            return@withContext Result.success()
        }

        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val title = "Gesichtserkennung · ${model.tier}"
        safeForeground(Notifications.progress(applicationContext, Notifications.ID_FACES, title, "Wird vorbereitet …", 0, todo.size, cancel))

        val engine = FaceEngine(model, c.models, c.settings.current.aiThreads)
        val resolver = applicationContext.contentResolver
        var done = 0
        var totalMs = 0L
        var lastUi = 0L
        var sinceAssign = 0
        try {
            for (item in todo) {
                if (isStopped) break
                val t0 = System.currentTimeMillis()
                val faces = runCatching {
                    val bmp = resolver.loadThumbnail(item.uri, Size(THUMB, THUMB), null)
                    val soft = if (bmp.config == android.graphics.Bitmap.Config.HARDWARE) bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else bmp
                    try {
                        engine.detect(soft).take(MAX_FACES).map { d ->
                            val v = engine.embed(soft, d.landmarks)
                            FaceEntity(
                                mediaId = item.id,
                                modelId = model.id,
                                left = (d.box.left / soft.width).coerceIn(0f, 1f),
                                top = (d.box.top / soft.height).coerceIn(0f, 1f),
                                right = (d.box.right / soft.width).coerceIn(0f, 1f),
                                bottom = (d.box.bottom / soft.height).coerceIn(0f, 1f),
                                score = d.score,
                                embedding = Fp16.encode(v),
                            )
                        }
                    } finally {
                        if (soft !== bmp) soft.recycle()
                        bmp.recycle()
                    }
                }.getOrDefault(emptyList())
                // Re-scan of a changed file: replace its old faces.
                if (known.containsKey(item.id)) dao.deleteFacesOfMedia(model.id, listOf(item.id))
                if (faces.isNotEmpty()) dao.insertFaces(faces)
                dao.upsertScan(FaceScanEntity(item.id, model.id, item.dateModified, faces.size))
                totalMs += System.currentTimeMillis() - t0
                done++
                sinceAssign += faces.size

                if (sinceAssign >= 200) {
                    c.faces.assignUnassigned(model)
                    sinceAssign = 0
                }
                val now = System.currentTimeMillis()
                if (now - lastUi > 800 || done == todo.size) {
                    lastUi = now
                    val ms = (totalMs / done).toInt()
                    setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to todo.size, KEY_MS to ms))
                    safeForeground(
                        Notifications.progress(
                            applicationContext, Notifications.ID_FACES, title,
                            "$done von ${todo.size} Fotos", done, todo.size, cancel
                        )
                    )
                }
            }
        } finally {
            engine.close()
        }
        c.faces.assignUnassigned(model)
        if (done == todo.size && todo.size > 20) {
            Notifications.done(applicationContext, "Gesichter erkannt", "Unter „Personen“ kannst du jetzt Namen vergeben.")
        }
        Result.success()
    }

    companion object {
        const val NAME = "face-index"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_MS = "ms"
        private const val THUMB = 1280
        private const val MAX_FACES = 30
    }
}
