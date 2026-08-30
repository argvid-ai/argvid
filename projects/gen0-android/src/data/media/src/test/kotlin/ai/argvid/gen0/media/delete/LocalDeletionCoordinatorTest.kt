package ai.argvid.gen0.media.delete

import ai.argvid.gen0.media.db.MomentDbStatus
import ai.argvid.gen0.media.db.MomentEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalDeletionCoordinatorTest {
    @Test
    fun mediaStoreFailureLeavesRetryablePendingState() = runTest {
        val harness = Harness(mediaResult = DeleteAssetResult.Retryable)

        val receipt = harness.coordinator.deleteLocal("m1")

        assertEquals(LocalDeletionState.RETRY_REQUIRED, receipt.localState)
        assertEquals(MomentDbStatus.LOCAL_DELETE_PENDING, harness.store.moment.status)
        assertEquals(0, harness.staging.calls)
    }

    @Test
    fun missingUriStillAllowsMetadataAndStagingDeletion() = runTest {
        val harness = Harness(mediaResult = DeleteAssetResult.AlreadyMissing)

        val receipt = harness.coordinator.deleteLocal("m1")

        assertEquals(LocalDeletionState.COMPLETE, receipt.localState)
        assertEquals(MomentDbStatus.DELETED, harness.store.moment.status)
        assertNull(harness.store.moment.mediaUri)
        assertNull(harness.store.moment.stagingPath)
        assertEquals(1, harness.staging.calls)
    }

    @Test
    fun stagingFailureIsRetryableAfterMediaWasDeleted() = runTest {
        val harness = Harness(stagingResult = DeleteStagingResult.Retryable)

        val first = harness.coordinator.deleteLocal("m1")
        harness.staging.result = DeleteStagingResult.Deleted
        harness.media.result = DeleteAssetResult.AlreadyMissing
        val afterProcessRestart = LocalDeletionCoordinator(
            harness.store,
            harness.media,
            harness.staging,
            now = { "2026-08-29T19:00:00Z" },
        ).deleteLocal("m1")

        assertEquals(LocalDeletionState.RETRY_REQUIRED, first.localState)
        assertEquals(LocalDeletionState.COMPLETE, afterProcessRestart.localState)
        assertEquals(2, harness.media.calls)
        assertEquals(2, harness.staging.calls)
    }

    @Test
    fun repeatedRequestReturnsExistingReceiptWithoutDeletingAgain() = runTest {
        val harness = Harness()
        val first = harness.coordinator.deleteLocal("m1")

        val second = harness.coordinator.deleteLocal("m1")

        assertEquals(first, second)
        assertEquals(1, harness.media.calls)
        assertEquals(1, harness.staging.calls)
    }

}

private class Harness(
    mediaResult: DeleteAssetResult = DeleteAssetResult.Deleted,
    stagingResult: DeleteStagingResult = DeleteStagingResult.Deleted,
) {
    val store = FakeDeletionStore()
    val media = FakeMediaDeleter(mediaResult)
    val staging = FakeStagingStore(stagingResult)
    val coordinator = LocalDeletionCoordinator(
        store,
        media,
        staging,
        now = { "2026-08-29T19:00:00Z" },
    )
}

private class FakeDeletionStore : LocalDeletionStore {
    var moment = deletionMoment()
    override suspend fun get(momentId: String): MomentEntity? = moment.takeIf { it.id == momentId }
    override suspend fun markPending(momentId: String): Int {
        moment = moment.copy(status = MomentDbStatus.LOCAL_DELETE_PENDING)
        return 1
    }
    override suspend fun complete(momentId: String, receiptAt: String): Int {
        moment = moment.copy(
            status = MomentDbStatus.DELETED,
            mediaUri = null,
            stagingPath = null,
            localDeletionReceiptAt = receiptAt,
        )
        return 1
    }
}

private class FakeMediaDeleter(var result: DeleteAssetResult) : LocalMediaDeleter {
    var calls = 0
    override suspend fun delete(uri: String): DeleteAssetResult {
        calls += 1
        return result
    }
}

private class FakeStagingStore(var result: DeleteStagingResult) : PrivateStagingStore {
    var calls = 0
    override suspend fun delete(path: String): DeleteStagingResult {
        calls += 1
        return result
    }
}

private fun deletionMoment() = MomentEntity(
    id = "m1",
    sessionId = "s1",
    source = "manual_rescue",
    qualityTier = "Proxy",
    mediaUri = "content://moment/1",
    durationUs = 15_000_000,
    createdAt = "2026-08-29T18:00:00Z",
    status = MomentDbStatus.SAVED,
    viewedAt = null,
    stagingPath = "/private/cache/staged.mp4",
    localDeletionReceiptAt = null,
)
