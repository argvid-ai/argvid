package ai.argvid.gen0.today

import ai.argvid.gen0.media.catalog.AssetError
import ai.argvid.gen0.media.catalog.TodayAssetResult
import ai.argvid.gen0.media.catalog.TodayMoment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
    @Test
    fun slowVerificationCannotRestoreAPreviousSelection() = runTest {
        val first = moment()
        val second = first.copy(id = "m2", mediaUri = "content://moment/2")
        val gate = CompletableDeferred<Unit>()
        var delayFirst = false
        val source = object : TodaySource {
            override val moments = MutableStateFlow(listOf(first, second))
            override suspend fun refresh(momentId: String): TodayAssetResult {
                if (momentId == first.id && delayFirst) {
                    withContext(NonCancellable) { gate.await() }
                }
                return TodayAssetResult.Playable(if (momentId == first.id) first else second)
            }
            override suspend fun markViewed(momentId: String, viewedAt: String) = Unit
        }
        val viewModel = TodayViewModel(source, FakeMomentPlayer(), injectedScope = backgroundScope)
        runCurrent()
        delayFirst = true
        viewModel.retry()
        runCurrent()
        viewModel.selectMoment(second.id)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(TodayUiState.Ready(second, false), viewModel.state.value)
    }

    @Test
    fun completedDeletionReceiptSurvivesAnotherDeleteAttemptUntilExplicitlyCleared() = runTest {
        val first = moment()
        val second = first.copy(id = "m2", mediaUri = "content://moment/2")
        val source = FakeLibrarySource(listOf(first, second))
        val deleted = mutableListOf<String>()
        val cleared = mutableListOf<String>()
        val deletion = object : LocalMomentDeletion {
            override suspend fun delete(momentId: String): LocalDeletionUiResult {
                deleted += momentId
                source.moments.value = source.moments.value.filterNot { it.id == momentId }
                return LocalDeletionUiResult.Complete
            }
            override suspend fun clearRecord(momentId: String): Boolean {
                cleared += momentId
                return true
            }
        }
        val viewModel = TodayViewModel(source, FakeMomentPlayer(), injectedScope = backgroundScope,
            deletion = deletion)
        runCurrent()
        assertEquals(TodayUiState.Ready(first, false), viewModel.state.value)

        viewModel.requestLocalDeletion()
        viewModel.confirmLocalDeletion()
        runCurrent()
        assertEquals(listOf(first.id), deleted)
        assertEquals(listOf(second), viewModel.moments.value)
        assertEquals(TodayUiState.Ready(second, false), viewModel.state.value)
        assertEquals(DeletionUiState.Complete(first.id), viewModel.deletionState.value)

        viewModel.requestLocalDeletion()
        assertEquals(DeletionUiState.Complete(first.id), viewModel.deletionState.value)
        viewModel.dismissLocalDeletion()
        assertEquals(DeletionUiState.Complete(first.id), viewModel.deletionState.value)
        assertEquals(emptyList<String>(), cleared)
        assertEquals(listOf(first.id), deleted)

        viewModel.clearLocalRecord()
        runCurrent()
        assertEquals(listOf(first.id), cleared)
        assertEquals(DeletionUiState.RecordCleared(first.id), viewModel.deletionState.value)

        viewModel.requestLocalDeletion()
        assertEquals(DeletionUiState.Confirm(second.id, second.createdAt), viewModel.deletionState.value)
        viewModel.dismissLocalDeletion()
        assertEquals(DeletionUiState.None, viewModel.deletionState.value)
        assertEquals(listOf(first.id), deleted)
    }

    @Test
    fun retryAndRecordClearingKeepTheOriginalDeletionIdentity() = runTest {
        val first = moment()
        val second = first.copy(id = "m2", mediaUri = "content://moment/2")
        val source = FakeLibrarySource(listOf(first, second))
        val deleted = mutableListOf<String>()
        val cleared = mutableListOf<String>()
        val deletion = object : LocalMomentDeletion {
            override suspend fun delete(momentId: String): LocalDeletionUiResult {
                deleted += momentId
                source.moments.value = listOf(second)
                return if (deleted.size == 1) LocalDeletionUiResult.RetryRequired
                    else LocalDeletionUiResult.Complete
            }
            override suspend fun clearRecord(momentId: String): Boolean {
                cleared += momentId
                return true
            }
        }
        val viewModel = TodayViewModel(source, FakeMomentPlayer(), injectedScope = backgroundScope,
            deletion = deletion)
        runCurrent()
        viewModel.requestLocalDeletion()
        viewModel.confirmLocalDeletion()
        runCurrent()
        assertEquals(DeletionUiState.RetryRequired(first.id), viewModel.deletionState.value)
        viewModel.selectMoment(second.id)
        runCurrent()
        viewModel.requestLocalDeletion()
        viewModel.confirmLocalDeletion()
        viewModel.retryLocalDeletion()
        runCurrent()
        viewModel.clearLocalRecord()
        runCurrent()
        assertEquals(listOf(first.id, first.id), deleted)
        assertEquals(listOf(first.id), cleared)
        assertEquals(TodayUiState.Ready(second, false), viewModel.state.value)
    }

    @Test
    fun selectingAnOlderClipSurvivesNewSavesAndDeletesOnlyThatClip() = runTest {
        val old = moment()
        val newest = old.copy(id = "m2", mediaUri = "content://moment/2",
            createdAt = "2026-08-29T18:01:00Z")
        val incoming = newest.copy(id = "m3", mediaUri = "content://moment/3")
        val source = FakeLibrarySource(listOf(newest, old))
        val deleted = mutableListOf<String>()
        val player = FakeMomentPlayer()
        val viewModel = TodayViewModel(source, player, injectedScope = backgroundScope,
            deletion = LocalMomentDeletion {
                deleted += it
                source.moments.value = source.moments.value.filterNot { moment -> moment.id == it }
                LocalDeletionUiResult.Complete
            })
        runCurrent()
        assertEquals(listOf(newest, old), viewModel.moments.value)
        viewModel.selectMoment(old.id)
        runCurrent()
        viewModel.play()
        assertEquals(old.mediaUri, player.playedUris.last())
        viewModel.requestLocalDeletion()
        source.moments.value = listOf(incoming, newest, old)
        viewModel.refreshLibrary()
        runCurrent()
        assertEquals(old, (viewModel.state.value as TodayUiState.Ready).moment)
        viewModel.confirmLocalDeletion()
        runCurrent()
        assertEquals(listOf(old.id), deleted)
        assertEquals(listOf(incoming, newest), viewModel.moments.value)
    }

    @Test
    fun switchingSelectionDuringConfirmationDoesNotChangeTheDeleteTarget() = runTest {
        val first = moment()
        val second = first.copy(id = "m2", mediaUri = "content://moment/2")
        val source = FakeLibrarySource(listOf(second, first))
        val deleted = mutableListOf<String>()
        val viewModel = TodayViewModel(source, FakeMomentPlayer(), injectedScope = backgroundScope,
            deletion = LocalMomentDeletion { deleted += it; LocalDeletionUiResult.Complete })
        runCurrent()
        viewModel.selectMoment(first.id)
        runCurrent()
        viewModel.requestLocalDeletion()
        viewModel.selectMoment(second.id)
        runCurrent()
        viewModel.confirmLocalDeletion()
        runCurrent()
        assertEquals(listOf(first.id), deleted)
    }

    @Test
    fun changingTheDisplayedMomentReleasesThePreviousVideo() = runTest {
        val first = moment()
        val second = first.copy(id = "m2", mediaUri = "content://moment/2")
        val source = FakeTodaySource(first, TodayAssetResult.Playable(first))
        val player = FakeMomentPlayer()
        val viewModel = TodayViewModel(source, player, injectedScope = backgroundScope)
        runCurrent()
        viewModel.play()
        source.result = TodayAssetResult.Playable(second)
        source.latest.value = second
        runCurrent()
        assertEquals(1, player.releaseCalls)
        assertEquals(TodayUiState.Ready(second, false), viewModel.state.value)
        player.events.emit(PlayerEvent.FirstFrameRendered)
        runCurrent()
        assertEquals(emptyList<Pair<String, String>>(), source.viewed)
    }

    @Test
    fun deletionReleasesPlaybackAndKeepsTheConfirmedIdentityAcrossRefresh() = runTest {
        val first = moment()
        val second = first.copy(id = "m2", mediaUri = "content://moment/2")
        val source = FakeTodaySource(first, TodayAssetResult.Playable(first))
        val player = FakeMomentPlayer()
        val deleted = mutableListOf<String>()
        val viewModel = TodayViewModel(source, player, injectedScope = backgroundScope,
            deletion = LocalMomentDeletion { deleted += it; LocalDeletionUiResult.Complete })
        runCurrent()
        viewModel.play()
        viewModel.requestLocalDeletion()
        viewModel.confirmLocalDeletion()
        assertEquals(1, player.releaseCalls)
        source.result = TodayAssetResult.Playable(second)
        source.latest.value = second
        runCurrent()
        assertEquals(listOf("m1"), deleted)
    }

    @Test
    fun pendingDeletionCannotBeReplacedByAnotherMoment() = runTest {
        val first = moment()
        val second = first.copy(id = "m2", mediaUri = "content://moment/2")
        val source = FakeTodaySource(first, TodayAssetResult.Playable(first))
        val gate = CompletableDeferred<Unit>()
        val deleted = mutableListOf<String>()
        val viewModel = TodayViewModel(source, FakeMomentPlayer(), injectedScope = backgroundScope,
            deletion = LocalMomentDeletion {
                deleted += it
                gate.await()
                LocalDeletionUiResult.Complete
            })
        runCurrent()
        viewModel.requestLocalDeletion()
        viewModel.confirmLocalDeletion()
        runCurrent()
        source.result = TodayAssetResult.Playable(second)
        source.latest.value = second
        runCurrent()
        viewModel.requestLocalDeletion()
        viewModel.confirmLocalDeletion()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("m1"), deleted)
    }

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
    fun refreshLatestDiscoversARecordAfterTheViewModelWasInitiallyEmpty() = runTest {
        val source = FakeTodaySource(null, TodayAssetResult.AssetMissing("none"))
        val viewModel = TodayViewModel(source, FakeMomentPlayer(), injectedScope = backgroundScope)
        runCurrent()

        val saved = moment()
        source.result = TodayAssetResult.Playable(saved)
        source.latest.value = saved
        viewModel.refreshLibrary()
        runCurrent()

        assertEquals(TodayUiState.Ready(saved, isPlaying = false), viewModel.state.value)
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
        assertEquals(DeletionUiState.Confirm("m1", moment.createdAt), viewModel.deletionState.value)

        assertEquals(0, deletion.calls)

        viewModel.confirmLocalDeletion()
        runCurrent()

        assertEquals(
            DeletionUiState.Complete("m1"),
            viewModel.deletionState.value,
        )
        assertEquals(1, deletion.calls)
    }
}

private class FakeTodaySource(
    moment: TodayMoment?,
    var result: TodayAssetResult,
) : TodaySource {
    val latest = MutableStateFlow(moment)
    override val moments = latest.map { listOfNotNull(it) }
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
    val playedUris = mutableListOf<String>()
    override fun play(uri: String) { playCalls++; playedUris += uri }
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

private class FakeLibrarySource(initial: List<TodayMoment>) : TodaySource {
    override val moments = MutableStateFlow(initial)
    override suspend fun refresh(momentId: String): TodayAssetResult =
        moments.value.find { it.id == momentId }?.let { TodayAssetResult.Playable(it) }
            ?: TodayAssetResult.AssetMissing(momentId)
    override suspend fun markViewed(momentId: String, viewedAt: String) = Unit
}
