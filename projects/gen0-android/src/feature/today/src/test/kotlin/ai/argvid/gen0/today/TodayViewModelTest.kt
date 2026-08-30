package ai.argvid.gen0.today

import ai.argvid.gen0.media.catalog.AssetError
import ai.argvid.gen0.media.catalog.TodayAssetResult
import ai.argvid.gen0.media.catalog.TodayMoment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
    @Test
    fun playAfterStopUsesTheSamePlayerOwnerAgain() = runTest {
        val moment = moment()
        val player = FakeMomentPlayer()
        val viewModel = TodayViewModel(FakeTodaySource(moment, TodayAssetResult.Playable(moment)),
            player, injectedScope = backgroundScope)
        runCurrent()
        viewModel.play()
        viewModel.onStop()
        viewModel.play()
        assertEquals(2, player.playCalls)
        assertEquals(1, player.releaseCalls)
    }

    @Test
    fun firstRenderedFrameMarksMomentViewed() = runTest {
        val moment = moment()
        val source = FakeTodaySource(moment, TodayAssetResult.Playable(moment))
        val player = FakeMomentPlayer()
        val viewModel = TodayViewModel(source, player, { "2026-08-29T18:01:00Z" }, backgroundScope)
        runCurrent()

        viewModel.play()
        player.events.emit(PlayerEvent.FirstFrameRendered)
        player.events.emit(PlayerEvent.FirstFrameRendered)
        runCurrent()

        assertEquals(listOf("m1" to "2026-08-29T18:01:00Z"), source.viewed)
        assertEquals(TodayUiState.Ready(moment, isPlaying = true), viewModel.state.value)
    }

    @Test
    fun confirmedMissingStateIsNotOverwrittenBySavedOnlyFlowBecomingEmpty() = runTest {
        val moment = moment()
        val source = FakeTodaySource(moment, TodayAssetResult.AssetMissing("m1"))
        val viewModel = TodayViewModel(source, FakeMomentPlayer(), { "now" }, backgroundScope)
        runCurrent()

        source.latest.value = null
        runCurrent()

        assertEquals(TodayUiState.AssetMissing("m1"), viewModel.state.value)
    }

    @Test
    fun retryableVerificationOffersRetryWithoutMarkingMissing() = runTest {
        val moment = moment()
        val source = FakeTodaySource(
            moment,
            TodayAssetResult.Retryable("m1", AssetError.PermissionTemporarilyUnavailable),
        )

        val viewModel = TodayViewModel(source, FakeMomentPlayer(), { "now" }, backgroundScope)
        runCurrent()

        assertEquals(
            TodayUiState.RetryableError("m1", TodayErrorCode.AssetTemporarilyUnavailable),
            viewModel.state.value,
        )
    }

    @Test
    fun lifecycleStopReleasesPlayer() = runTest {
        val source = FakeTodaySource(null, TodayAssetResult.AssetMissing("none"))
        val player = FakeMomentPlayer()
        val viewModel = TodayViewModel(source, player, { "now" }, backgroundScope)

        viewModel.onStop()

        assertEquals(1, player.releaseCalls)
    }

    @Test
    fun deletionRequiresConfirmationAndReportsLocalCompletion() = runTest {
        val moment = moment()
        val deletion = FakeLocalMomentDeletion()
        val viewModel = TodayViewModel(
            FakeTodaySource(moment, TodayAssetResult.Playable(moment)),
            FakeMomentPlayer(),
            { "now" },
            backgroundScope,
            deletion,
        )
        runCurrent()

        viewModel.requestLocalDeletion()
        assertEquals(DeletionUiState.Confirm("m1"), viewModel.deletionState.value)

        assertEquals(0, deletion.calls)

        viewModel.confirmLocalDeletion()
        runCurrent()

        assertEquals(
            DeletionUiState.Complete,
            viewModel.deletionState.value,
        )
        assertEquals(1, deletion.calls)
    }
}

private class FakeTodaySource(
    moment: TodayMoment?,
    var result: TodayAssetResult,
) : TodaySource {
    override val latest = MutableStateFlow(moment)
    val viewed = mutableListOf<Pair<String, String>>()
    override suspend fun refresh(momentId: String): TodayAssetResult = result
    override suspend fun markViewed(momentId: String, viewedAt: String) {
        viewed += momentId to viewedAt
    }
}

private class FakeMomentPlayer : MomentPlayer {
    override val events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 1)
    var releaseCalls = 0
    var playCalls = 0
    override fun play(uri: String) { playCalls++ }
    override fun release() { releaseCalls += 1 }
}

private class FakeLocalMomentDeletion : LocalMomentDeletion {
    var calls = 0
    override suspend fun delete(momentId: String): LocalDeletionUiResult {
        calls += 1
        return LocalDeletionUiResult.Complete
    }
}

private fun moment() = TodayMoment(
    id = "m1",
    mediaUri = "content://moment/1",
    durationUs = 15_000_000,
    createdAt = "2026-08-29T18:00:00Z",
    qualityTier = "PROXY",
)
