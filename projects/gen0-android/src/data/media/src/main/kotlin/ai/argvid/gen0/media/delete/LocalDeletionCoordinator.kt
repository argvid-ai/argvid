package ai.argvid.gen0.media.delete

import ai.argvid.gen0.media.db.MomentDao
import ai.argvid.gen0.media.db.MomentDbStatus
import ai.argvid.gen0.media.db.MomentEntity
import ai.argvid.gen0.media.store.MediaStoreClient
import ai.argvid.gen0.media.store.MediaStoreTarget
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DeleteAssetResult {
    Deleted,
    AlreadyMissing,
    Retryable,
}

fun interface LocalMediaDeleter {
    suspend fun delete(uri: String): DeleteAssetResult
}

class MediaStoreLocalMediaDeleter(
    private val client: MediaStoreClient,
) : LocalMediaDeleter {
    override suspend fun delete(uri: String): DeleteAssetResult = try {
        if (client.delete(MediaStoreTarget(uri)) > 0) {
            DeleteAssetResult.Deleted
        } else {
            DeleteAssetResult.AlreadyMissing
        }
    } catch (_: SecurityException) {
        DeleteAssetResult.Retryable
    } catch (_: RuntimeException) {
        DeleteAssetResult.Retryable
    }
}

interface LocalDeletionStore {
    suspend fun get(momentId: String): MomentEntity?
    suspend fun markPending(momentId: String): Int
    suspend fun complete(momentId: String, receiptAt: String): Int
    suspend fun clearRecord(momentId: String): Boolean = false
}

class RoomLocalDeletionStore(private val dao: MomentDao) : LocalDeletionStore {
    override suspend fun get(momentId: String) = dao.get(momentId)
    override suspend fun markPending(momentId: String) = dao.markLocalDeletePending(momentId)
    override suspend fun complete(momentId: String, receiptAt: String) =
        dao.completeLocalDeletion(momentId, receiptAt)
    override suspend fun clearRecord(momentId: String) = dao.clearDeletedRecord(momentId) == 1
}

class LocalDeletionCoordinator(
    private val store: LocalDeletionStore,
    private val media: LocalMediaDeleter,
    private val staging: PrivateStagingStore,
    private val now: () -> String = { Instant.now().toString() },
) {
    private val mutex = Mutex()

    suspend fun deleteLocal(momentId: String): DeletionReceipt = mutex.withLock {
        var moment = store.get(momentId) ?: return@withLock receipt(
            momentId,
            LocalDeletionState.NOT_FOUND,
            null,
        )
        if (moment.status == MomentDbStatus.DELETED && moment.localDeletionReceiptAt != null) {
            return@withLock receipt(
                momentId,
                LocalDeletionState.COMPLETE,
                moment.localDeletionReceiptAt,
            )
        }

        if (moment.status != MomentDbStatus.LOCAL_DELETE_PENDING) {
            if (store.markPending(momentId) == 0) {
                return@withLock terminalOrRetry(momentId)
            }
            moment = checkNotNull(store.get(momentId))
        }

        val mediaResult = moment.mediaUri?.let { media.delete(it) } ?: DeleteAssetResult.AlreadyMissing
        if (mediaResult == DeleteAssetResult.Retryable) {
            return@withLock retry(momentId)
        }

        val stagingResult = moment.stagingPath?.let { staging.delete(it) } ?: DeleteStagingResult.AlreadyMissing
        if (stagingResult == DeleteStagingResult.Retryable) {
            return@withLock retry(momentId)
        }

        val deletedAt = now()
        if (store.complete(momentId, deletedAt) == 0) {
            return@withLock terminalOrRetry(momentId)
        }
        receipt(momentId, LocalDeletionState.COMPLETE, deletedAt)
    }

    suspend fun clearRecord(momentId: String): Boolean = mutex.withLock {
        store.clearRecord(momentId)
    }

    private fun retry(momentId: String) =
        receipt(momentId, LocalDeletionState.RETRY_REQUIRED, null)

    private suspend fun terminalOrRetry(momentId: String): DeletionReceipt {
        val current = store.get(momentId)
        return if (current?.status == MomentDbStatus.DELETED && current.localDeletionReceiptAt != null) {
            receipt(
                momentId,
                LocalDeletionState.COMPLETE,
                current.localDeletionReceiptAt,
            )
        } else {
            retry(momentId)
        }
    }

    private fun receipt(
        momentId: String,
        localState: LocalDeletionState,
        localDeletedAt: String?,
    ) = DeletionReceipt(momentId, localState, localDeletedAt)
}
