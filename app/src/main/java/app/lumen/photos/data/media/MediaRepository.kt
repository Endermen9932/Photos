package app.lumen.photos.data.media

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single source of truth for everything in the device's MediaStore. Keeps an in-memory snapshot
 * of all photos and videos and refreshes it whenever the MediaStore changes.
 */
class MediaRepository(private val context: Context, private val scope: CoroutineScope) {

    private val resolver = context.contentResolver
    private val _media = MutableStateFlow<List<MediaItem>>(emptyList())
    private val _loaded = MutableStateFlow(false)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val loadMutex = Mutex()
    private var observerRegistered = false

    /** All non-trashed media, newest first. */
    val media: StateFlow<List<MediaItem>> = _media.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val albums: StateFlow<List<Album>> = _media
        .map { list -> buildAlbums(list) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Incremented on every reload so screens depending on derived queries can refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            changes.tryEmit(Unit)
        }
    }

    @OptIn(FlowPreview::class)
    fun start() {
        if (!observerRegistered) {
            observerRegistered = true
            resolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
            changes.debounce(700).onEach { reload() }.launchIn(scope)
        }
        scope.launch { reload() }
    }

    fun refresh() {
        scope.launch { reload() }
    }

    suspend fun reload() = loadMutex.withLock {
        val list = withContext(Dispatchers.IO) { query(trashed = false) }
        _media.value = list
        _loaded.value = true
        _version.value++
    }

    suspend fun trashed(): List<MediaItem> = withContext(Dispatchers.IO) { query(trashed = true) }

    fun byId(id: Long): MediaItem? = _media.value.firstOrNull { it.id == id }

    private fun query(trashed: Boolean): List<MediaItem> {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.DATE_EXPIRES,
        )
        val args = Bundle().apply {
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            )
            putStringArray(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                )
            )
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
        }
        val result = ArrayList<MediaItem>(4096)
        runCatching {
            resolver.query(uri, projection, args, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val iMod = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val iW = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val iH = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val iOr = c.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
                val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
                val iBucketName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                val iFav = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
                val iExp = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val isVideo = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val contentUri = ContentUris.withAppendedId(
                        if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val modified = c.getLong(iMod)
                    val taken = c.getLong(iTaken)
                    result += MediaItem(
                        id = id,
                        uri = contentUri,
                        name = c.getString(iName) ?: "",
                        mimeType = c.getString(iMime) ?: if (isVideo) "video/*" else "image/*",
                        timestamp = if (taken > 0) taken else modified * 1000,
                        dateModified = modified,
                        size = c.getLong(iSize),
                        width = c.getInt(iW),
                        height = c.getInt(iH),
                        orientation = c.getInt(iOr),
                        bucketId = c.getLong(iBucket),
                        bucketName = c.getString(iBucketName) ?: "",
                        relativePath = c.getString(iPath) ?: "",
                        durationMs = c.getLong(iDur),
                        isFavorite = c.getInt(iFav) == 1,
                        isTrashed = trashed,
                        expiresAt = c.getLong(iExp),
                    )
                }
            }
        }
        result.sortByDescending { it.timestamp }
        return result
    }

    private fun buildAlbums(list: List<MediaItem>): List<Album> =
        list.groupBy { it.bucketId }
            .map { (id, items) ->
                Album(
                    id = id,
                    name = items.first().bucketName.ifBlank { "Unbenannt" },
                    relativePath = items.first().relativePath,
                    cover = items.first(),
                    count = items.size,
                    totalSize = items.sumOf { it.size },
                    latest = items.first().timestamp,
                )
            }
            .sortedWith(compareByDescending<Album> { albumPriority(it) }.thenByDescending { it.latest })

    private fun albumPriority(album: Album): Int = when {
        album.relativePath.startsWith("DCIM/Camera", ignoreCase = true) -> 3
        album.relativePath.contains("Screenshots", ignoreCase = true) -> 2
        else -> 0
    }

    // ---- Actions that need user consent. Each returns an IntentSender the UI has to launch. ----

    fun trashRequest(uris: Collection<Uri>, trash: Boolean): IntentSender =
        MediaStore.createTrashRequest(resolver, uris, trash).intentSender

    fun favoriteRequest(uris: Collection<Uri>, favorite: Boolean): IntentSender =
        MediaStore.createFavoriteRequest(resolver, uris, favorite).intentSender

    fun deleteRequest(uris: Collection<Uri>): IntentSender =
        MediaStore.createDeleteRequest(resolver, uris).intentSender

    fun writeRequest(uris: Collection<Uri>): IntentSender =
        MediaStore.createWriteRequest(resolver, uris).intentSender

    fun writeRequestPendingIntent(uris: Collection<Uri>): PendingIntent =
        MediaStore.createWriteRequest(resolver, uris)

    /** True if the app may modify media without a confirmation dialog (Android "media management" access). */
    fun canManageMedia(): Boolean = MediaStore.canManageMedia(context)

    /** Moves files into another folder. Requires write access (see [writeRequest]). */
    suspend fun move(items: List<MediaItem>, relativePath: String): Int = withContext(Dispatchers.IO) {
        var ok = 0
        val path = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
        for (item in items) {
            runCatching {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, path) }
                if (resolver.update(item.uri, values, null, null) > 0) ok++
            }
        }
        reload()
        ok
    }

    suspend fun rename(item: MediaItem, newName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
            resolver.update(item.uri, values, null, null) > 0
        }.getOrDefault(false).also { if (it) reload() }
    }

    companion object {
        fun isCameraFolder(path: String) = path.startsWith("DCIM/", ignoreCase = true)
    }
}
