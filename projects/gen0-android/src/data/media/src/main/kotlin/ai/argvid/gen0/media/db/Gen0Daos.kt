package ai.argvid.gen0.media.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(session: SessionEntity): Long
}

@Dao
interface MomentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(moment: MomentEntity)

    @Query("SELECT * FROM moments WHERE id = :id")
    suspend fun get(id: String): MomentEntity?

    @Query(
        "SELECT * FROM moments WHERE status IN ('SAVED','SAVED_WITH_CLEANUP_PENDING') " +
            "ORDER BY createdAt DESC LIMIT 1",
    )
    fun observeLatestPlayableCandidate(): Flow<MomentEntity?>

    @Query(
        "SELECT * FROM moments WHERE status IN ('SAVED','SAVED_WITH_CLEANUP_PENDING') " +
            "ORDER BY createdAt DESC LIMIT 1",
    )
    suspend fun latestPlayableCandidate(): MomentEntity?

    @Query("UPDATE moments SET status = :status, mediaUri = :mediaUri WHERE id = :id")
    suspend fun updateAssetState(id: String, status: MomentDbStatus, mediaUri: String?): Int

    @Query("UPDATE moments SET viewedAt = :viewedAt WHERE id = :id")
    suspend fun markViewed(id: String, viewedAt: String): Int

    @Query(
        "UPDATE moments SET stagingPath = NULL, status = 'SAVED' WHERE mediaUri = :uri " +
            "AND status = 'SAVED_WITH_CLEANUP_PENDING'",
    )
    suspend fun markStagingCleaned(uri: String): Int

    @Query(
        "UPDATE moments SET status = 'LOCAL_DELETE_PENDING' WHERE id = :id " +
            "AND status IN ('SAVED','SAVED_WITH_CLEANUP_PENDING','ASSET_MISSING','LOCAL_DELETE_PENDING')",
    )
    suspend fun markLocalDeletePending(id: String): Int

    @Query(
        "UPDATE moments SET status = 'DELETED', mediaUri = NULL, stagingPath = NULL, " +
            "localDeletionReceiptAt = :receiptAt WHERE id = :id AND status = 'LOCAL_DELETE_PENDING'",
    )
    suspend fun completeLocalDeletion(id: String, receiptAt: String): Int

    @Query("DELETE FROM moments WHERE id = :id AND status = 'DELETED'")
    suspend fun clearDeletedRecord(id: String): Int

    @Query("SELECT stagingPath FROM moments WHERE stagingPath IS NOT NULL")
    suspend fun stagingPaths(): List<String>
}
