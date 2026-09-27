package app.lumen.photos

import android.app.Application
import android.content.Context
import app.lumen.photos.ai.AiRepository
import app.lumen.photos.ai.ModelManager
import app.lumen.photos.ai.SearchIndex
import app.lumen.photos.data.db.LumenDatabase
import app.lumen.photos.data.media.MediaRepository
import app.lumen.photos.data.settings.SettingsRepository
import app.lumen.photos.face.FaceRepository
import app.lumen.photos.optimize.ImageOptimizer
import app.lumen.photos.ui.components.FaceCropFetcher
import app.lumen.photos.ui.components.FaceCropKeyer
import app.lumen.photos.ui.components.ThumbFetcher
import app.lumen.photos.ui.components.ThumbKeyer
import app.lumen.photos.work.Notifications
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container – small enough that a DI framework would only add weight. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context, scope)
    val db = LumenDatabase.create(context)
    val media = MediaRepository(context, scope)
    val models = ModelManager(context)
    val index = SearchIndex(db.embeddings())
    val ai = AiRepository(context, settings, models, db, index, media, scope)
    val optimizer = ImageOptimizer(context, db.optimized()) { id ->
        db.embeddings().markReplaced(id)
        db.faces().markReplaced(id)
    }
    val faces = FaceRepository(context, db.faces(), settings, models, media, scope)
    val lists = MediaListRegistry()
}

class LumenApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.ai.start()
        container.faces.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(ThumbFetcher.Factory(context))
                add(ThumbKeyer())
                add(FaceCropFetcher.Factory(context))
                add(FaceCropKeyer())
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("thumbs"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(180)
            .build()
}

val Context.container: AppContainer get() = (applicationContext as LumenApp).container
