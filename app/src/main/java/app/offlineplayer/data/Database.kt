package app.offlineplayer.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import app.offlineplayer.downloader.MediaMode
import kotlinx.coroutines.flow.Flow

enum class Status { QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED }

@Entity(tableName = "items")
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val mode: MediaMode,
    val maxHeight: Int?,
    val title: String,
    val remoteThumbnail: String?,
    val durationSec: Long?,
    val status: Status,
    val progress: Float = 0f,
    val etaSec: Long? = null,
    val error: String? = null,
    val filePath: String? = null,
    val thumbPath: String? = null,
    val fileSize: Long = 0,
    val estimatedSize: Long? = null,
    val positionMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
) {
    /** Local thumbnail once downloaded, otherwise YouTube's (needs internet). */
    val thumbnail: Any? get() = thumbPath?.let { java.io.File(it) } ?: remoteThumbnail
}

/** The play queue, persisted so it survives app restarts. */
@Entity(
    tableName = "queue",
    foreignKeys = [ForeignKey(Item::class, ["id"], ["itemId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("itemId")],
)
data class QueueEntry(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val position: Int,
    val itemId: Long,
)

data class IdPosition(val id: Long, val positionMs: Long)

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE status = 'COMPLETED' ORDER BY createdAt ASC")
    fun library(): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE status != 'COMPLETED' ORDER BY createdAt DESC")
    fun downloads(): Flow<List<Item>>

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM items WHERE status = 'COMPLETED'")
    fun totalBytes(): Flow<Long>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun get(id: Long): Item?

    @Query("SELECT * FROM items WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<Item>

    @Query("SELECT id, positionMs FROM items")
    suspend fun positions(): List<IdPosition>

    @Insert suspend fun insert(item: Item): Long
    @Update suspend fun update(item: Item)
    @Delete suspend fun delete(item: Item)

    @Query("UPDATE items SET status = :status, progress = :progress, etaSec = :eta WHERE id = :id")
    suspend fun setProgress(id: Long, status: Status, progress: Float, eta: Long?)

    @Query("UPDATE items SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: Status, error: String?)

    @Query("UPDATE items SET status = 'QUEUED', progress = 0, etaSec = NULL, error = NULL WHERE id = :id")
    suspend fun resetForRetry(id: Long)

    @Query("UPDATE items SET positionMs = :pos WHERE id = :id")
    suspend fun savePosition(id: Long, pos: Long)
}

@Dao
abstract class QueueDao {
    @Query("SELECT items.* FROM queue JOIN items ON items.id = queue.itemId ORDER BY queue.position")
    abstract suspend fun queueItems(): List<Item>

    @Query("DELETE FROM queue") protected abstract suspend fun clear()
    @Insert protected abstract suspend fun insertAll(entries: List<QueueEntry>)

    @Transaction
    open suspend fun replace(itemIds: List<Long>) {
        clear()
        insertAll(itemIds.mapIndexed { i, id -> QueueEntry(position = i, itemId = id) })
    }
}

@Database(entities = [Item::class, QueueEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun queue(): QueueDao

    companion object {
        fun build(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "offline-player.db").build()
    }
}
