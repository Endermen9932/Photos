package app.lumen.photos.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Image embedding of one media item for one model, stored as IEEE half floats. */
@Entity(tableName = "embeddings", primaryKeys = ["mediaId", "modelId"])
class EmbeddingEntity(
    val mediaId: Long,
    val modelId: String,
    val dateModified: Long,
    /** Empty if the file could not be processed (so it is not retried on every run). */
    val vector: ByteArray,
)

data class EmbeddingKey(val mediaId: Long, val dateModified: Long)

@Entity(tableName = "optimized", primaryKeys = ["mediaId"])
data class OptimizedEntity(
    val mediaId: Long,
    val originalSize: Long,
    val newSize: Long,
    val width: Int,
    val height: Int,
    val timestamp: Long,
)

data class OptimizedTotals(val count: Int, val saved: Long?)

@Dao
interface EmbeddingDao {
    @Query("SELECT mediaId, dateModified FROM embeddings WHERE modelId = :modelId")
    suspend fun keys(modelId: String): List<EmbeddingKey>

    @Query("SELECT * FROM embeddings WHERE modelId = :modelId AND length(vector) > 0 ORDER BY mediaId LIMIT :limit OFFSET :offset")
    suspend fun page(modelId: String, limit: Int, offset: Int): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE modelId = :modelId AND mediaId = :mediaId")
    suspend fun get(modelId: String, mediaId: Long): EmbeddingEntity?

    @Upsert
    suspend fun upsert(items: List<EmbeddingEntity>)

    @Query("DELETE FROM embeddings WHERE modelId = :modelId AND mediaId IN (:ids)")
    suspend fun delete(modelId: String, ids: List<Long>)

    @Query("DELETE FROM embeddings WHERE modelId = :modelId")
    suspend fun deleteModel(modelId: String)

    @Query("SELECT COUNT(*) FROM embeddings WHERE modelId = :modelId")
    fun countFlow(modelId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM embeddings WHERE modelId = :modelId")
    suspend fun count(modelId: String): Int
}

@Dao
interface OptimizedDao {
    @Query("SELECT mediaId FROM optimized")
    suspend fun ids(): List<Long>

    @Upsert
    suspend fun upsert(item: OptimizedEntity)

    @Query("SELECT COUNT(*) AS count, SUM(originalSize - newSize) AS saved FROM optimized")
    fun totals(): Flow<OptimizedTotals>
}

@Database(entities = [EmbeddingEntity::class, OptimizedEntity::class], version = 1, exportSchema = true)
abstract class LumenDatabase : RoomDatabase() {
    abstract fun embeddings(): EmbeddingDao
    abstract fun optimized(): OptimizedDao

    companion object {
        fun create(context: Context): LumenDatabase =
            Room.databaseBuilder(context, LumenDatabase::class.java, "lumen.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
