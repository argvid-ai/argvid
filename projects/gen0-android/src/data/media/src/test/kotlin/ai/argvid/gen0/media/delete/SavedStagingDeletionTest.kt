package ai.argvid.gen0.media.delete

import ai.argvid.gen0.domain.moment.*
import ai.argvid.gen0.media.catalog.toEntity
import ai.argvid.gen0.media.db.MomentDbStatus
import ai.argvid.gen0.media.db.MomentEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SavedStagingDeletionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun failedSaveCleanupRemainsLinkedUntilTodayDeletionReallyDeletesFile() = runTest {
        val staged = temporary.newFile("staged.mp4").apply { writeText("synthetic staged bytes") }
        val store = StagingDeletionStore()
        val coordinator = MomentCoordinator(
            source = object : MomentRescueSource {
                override suspend fun ownedMomentSnapshot(endingAtUs: Long, lookbackUs: Long) =
                    OwnedRescueAsset(emptyList(), 0, lookbackUs, true, QualityTier.Proxy)
            },
            encoder = object : MomentEncoder {
                override suspend fun encode(asset: OwnedRescueAsset) =
                    EncodedMoment(staged.path, 15_000_000, 960, 540, 0, QualityTier.Proxy)
                override suspend fun discard(moment: EncodedMoment) { error("storage temporarily unavailable") }
            },
            saver = MomentSaver { SavedMomentReference("content://media/1") },
            // Exercise the same record-to-Room-entity mapping as RoomMomentCatalog.
            catalog = MomentCatalog { store.moment = it.toEntity("s1", "m1", "now") },
        )
        assertEquals(MomentFailure.CleanupFailed, coordinator.captureRescue(15_000_000).failure)
        assertTrue(staged.exists())
        assertEquals(staged.path, store.moment.stagingPath)
        assertEquals(MomentDbStatus.SAVED_WITH_CLEANUP_PENDING, store.moment.status)
        val failedReceipt = LocalDeletionCoordinator(store, LocalMediaDeleter { DeleteAssetResult.Deleted },
            PrivateStagingStore { DeleteStagingResult.Retryable }).deleteLocal("m1")
        assertEquals(LocalDeletionState.RETRY_REQUIRED, failedReceipt.localState)
        assertTrue(staged.exists())
        assertNull(store.moment.localDeletionReceiptAt)
        assertEquals(staged.path, store.moment.stagingPath)
        val deletion = LocalDeletionCoordinator(store, LocalMediaDeleter { DeleteAssetResult.Deleted },
            AppPrivateStagingStore(temporary.root), now = { "receipt" })
        val receipt = deletion.deleteLocal("m1")
        assertEquals(LocalDeletionState.COMPLETE, receipt.localState)
        assertFalse(staged.exists())
        assertEquals("receipt", store.moment.localDeletionReceiptAt)
        assertNull(store.moment.stagingPath)
    }
}

private class StagingDeletionStore : LocalDeletionStore {
    lateinit var moment: MomentEntity
    override suspend fun get(momentId: String) = moment
    override suspend fun markPending(momentId: String): Int {
        moment = moment.copy(status = MomentDbStatus.LOCAL_DELETE_PENDING)
        return 1
    }
    override suspend fun complete(momentId: String, receiptAt: String): Int {
        moment = moment.copy(status = MomentDbStatus.DELETED, mediaUri = null,
            stagingPath = null, localDeletionReceiptAt = receiptAt)
        return 1
    }
}
