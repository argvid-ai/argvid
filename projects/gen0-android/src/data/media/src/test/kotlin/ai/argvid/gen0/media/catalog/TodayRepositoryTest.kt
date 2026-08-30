package ai.argvid.gen0.media.catalog

import ai.argvid.gen0.media.db.MomentDbStatus
import ai.argvid.gen0.media.db.MomentEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TodayRepositoryTest {
    @Test
    fun persistedStagingLinkRemainsVisibleInTodayAfterRecreation() = runTest {
        val store = FakeTodayMomentStore(savedMoment(MomentDbStatus.SAVED_WITH_CLEANUP_PENDING)
            .copy(stagingPath = "staged.mp4"))
        val repository = TodayRepository(store, AssetVerifier { AssetCheck.Present })
        assertEquals(true, repository.latest.first()?.cleanupPending)
        val recreated = TodayRepository(store, AssetVerifier { AssetCheck.Present })
        assertEquals(true, (recreated.refresh("m1") as TodayAssetResult.Playable).moment.cleanupPending)
    }

    @Test
    fun unreadableUriBecomesMissingOnlyWhenResolverConfirmsNotFound() = runTest {
        val store = FakeTodayMomentStore(savedMoment())
        val repository = TodayRepository(store, AssetVerifier { AssetCheck.NotFound })

        val result = repository.refresh("m1")

        assertEquals(TodayAssetResult.AssetMissing("m1"), result)
        assertEquals(MomentDbStatus.ASSET_MISSING, store.current.value?.status)
        assertEquals(null, store.current.value?.mediaUri)
    }

    @Test
    fun transientPermissionErrorDoesNotPretendAssetWasDeleted() = runTest {
        val store = FakeTodayMomentStore(savedMoment())
        val error = AssetError.PermissionTemporarilyUnavailable
        val repository = TodayRepository(store, AssetVerifier { AssetCheck.Retryable(error) })

        val result = repository.refresh("m1")

        assertEquals(TodayAssetResult.Retryable("m1", error), result)
        assertEquals(MomentDbStatus.SAVED, store.current.value?.status)
        assertEquals("content://moment/1", store.current.value?.mediaUri)
    }

    @Test
    fun onlySavedRowsAreExposedAndPresentAssetIsPlayable() = runTest {
        val store = FakeTodayMomentStore(savedMoment())
        val repository = TodayRepository(store, AssetVerifier { AssetCheck.Present })

        val latest = repository.latest.first()
        assertEquals("m1", latest?.id)
        assertEquals(
            TodayAssetResult.Playable(latest!!),
            repository.refresh("m1"),
        )

        store.current.value = savedMoment(status = MomentDbStatus.LOCAL_DELETE_PENDING)
        assertEquals(null, repository.latest.first())
    }
}

private class FakeTodayMomentStore(initial: MomentEntity?) : TodayMomentStore {
    val current = MutableStateFlow(initial)
    override val latestSaved = current

    override suspend fun get(id: String): MomentEntity? = current.value?.takeIf { it.id == id }

    override suspend fun updateAssetState(id: String, status: MomentDbStatus, mediaUri: String?): Int {
        val value = current.value ?: return 0
        if (value.id != id) return 0
        current.value = value.copy(status = status, mediaUri = mediaUri)
        return 1
    }

    override suspend fun markViewed(id: String, viewedAt: String): Int {
        val value = current.value ?: return 0
        current.value = value.copy(viewedAt = viewedAt)
        return 1
    }
}

private fun savedMoment(status: MomentDbStatus = MomentDbStatus.SAVED) = MomentEntity(
    id = "m1",
    sessionId = "s1",
    source = "manual_rescue",
    qualityTier = "PROXY",
    mediaUri = "content://moment/1",
    durationUs = 15_000_000,
    createdAt = "2026-08-29T18:00:00Z",
    status = status,
    viewedAt = null,
    stagingPath = null,
    localDeletionReceiptAt = null,
)
