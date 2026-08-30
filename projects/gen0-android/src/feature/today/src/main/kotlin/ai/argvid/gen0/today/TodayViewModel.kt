package ai.argvid.gen0.today

import ai.argvid.gen0.media.catalog.AssetError
import ai.argvid.gen0.media.catalog.TodayAssetResult
import ai.argvid.gen0.media.catalog.TodayMoment
import ai.argvid.gen0.media.catalog.TodayRepository
import ai.argvid.gen0.media.delete.LocalDeletionCoordinator
import ai.argvid.gen0.media.delete.LocalDeletionState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface TodaySource {
    val latest: Flow<TodayMoment?>
    suspend fun refresh(momentId: String): TodayAssetResult
    suspend fun markViewed(momentId: String, viewedAt: String)
}

sealed interface LocalDeletionUiResult {
    data object Complete : LocalDeletionUiResult
    data object RetryRequired : LocalDeletionUiResult
}

fun interface LocalMomentDeletion {
    suspend fun delete(momentId: String): LocalDeletionUiResult
    suspend fun clearRecord(momentId: String): Boolean = false
}

class CoordinatorLocalMomentDeletion(
    private val coordinator: LocalDeletionCoordinator,
) : LocalMomentDeletion {
    override suspend fun delete(momentId: String): LocalDeletionUiResult {
        val receipt = coordinator.deleteLocal(momentId)
        return when (receipt.localState) {
            LocalDeletionState.COMPLETE -> LocalDeletionUiResult.Complete
            LocalDeletionState.RETRY_REQUIRED,
            LocalDeletionState.NOT_FOUND,
            -> LocalDeletionUiResult.RetryRequired
        }
    }

    override suspend fun clearRecord(momentId: String) = coordinator.clearRecord(momentId)
}

class RepositoryTodaySource(private val repository: TodayRepository) : TodaySource {
    override val latest = repository.latest
    override suspend fun refresh(momentId: String) = repository.refresh(momentId)
    override suspend fun markViewed(momentId: String, viewedAt: String) = repository.markViewed(momentId, viewedAt)
}

class TodayViewModel(
    private val source: TodaySource,
    private val player: MomentPlayer,
    private val now: () -> String = { Instant.now().toString() },
    injectedScope: CoroutineScope? = null,
    private val deletion: LocalMomentDeletion? = null,
) : ViewModel() {
    private val scope = injectedScope ?: viewModelScope
    private val mutableState = MutableStateFlow<TodayUiState>(TodayUiState.Empty)
    private val mutableDeletionState = MutableStateFlow<DeletionUiState>(DeletionUiState.None)
    private var latestMoment: TodayMoment? = null
    private var viewedMomentId: String? = null
    private var deletingMomentId: String? = null
    val state: StateFlow<TodayUiState> = mutableState.asStateFlow()
    val deletionState: StateFlow<DeletionUiState> = mutableDeletionState.asStateFlow()

    init {
        scope.launch {
            source.latest.collect { moment ->
                latestMoment = moment
                if (moment == null) {
                    if (mutableState.value !is TodayUiState.AssetMissing) {
                        mutableState.value = TodayUiState.Empty
                    }
                } else {
                    apply(source.refresh(moment.id))
                }
            }
        }
        scope.launch {
            player.events.collect { event ->
                if (event == PlayerEvent.FirstFrameRendered) onFirstFrameRendered()
            }
        }
    }

    fun play() {
        val ready = mutableState.value as? TodayUiState.Ready ?: return
        player.play(ready.moment.mediaUri)
    }

    fun retry() {
        val moment = latestMoment ?: return
        scope.launch { apply(source.refresh(moment.id)) }
    }

    fun requestLocalDeletion() {
        val ready = mutableState.value as? TodayUiState.Ready ?: return
        if (deletion == null) return
        deletingMomentId = ready.moment.id
        mutableDeletionState.value = DeletionUiState.Confirm(ready.moment.id)
    }

    fun dismissLocalDeletion() {
        if (mutableDeletionState.value is DeletionUiState.Confirm) {
            mutableDeletionState.value = DeletionUiState.None
        }
    }

    fun confirmLocalDeletion() {
        val confirmation = mutableDeletionState.value as? DeletionUiState.Confirm ?: return
        deletingMomentId = confirmation.momentId
        performLocalDeletion(confirmation.momentId)
    }

    fun retryLocalDeletion() {
        val momentId = deletingMomentId ?: return
        performLocalDeletion(momentId)
    }

    fun clearLocalRecord() {
        val momentId = deletingMomentId ?: return
        val deleter = deletion ?: return
        scope.launch {
            if (deleter.clearRecord(momentId)) {
                mutableDeletionState.value = DeletionUiState.RecordCleared
                deletingMomentId = null
            }
        }
    }

    private fun performLocalDeletion(momentId: String) {
        val deleter = deletion ?: return
        mutableDeletionState.value = DeletionUiState.Deleting
        scope.launch {
            mutableDeletionState.value = when (deleter.delete(momentId)) {
                LocalDeletionUiResult.Complete -> DeletionUiState.Complete
                LocalDeletionUiResult.RetryRequired -> DeletionUiState.RetryRequired
            }
        }
    }

    fun onStop() {
        player.release()
        val ready = mutableState.value as? TodayUiState.Ready ?: return
        mutableState.value = ready.copy(isPlaying = false)
    }

    private suspend fun onFirstFrameRendered() {
        val ready = mutableState.value as? TodayUiState.Ready ?: return
        if (viewedMomentId != ready.moment.id) {
            source.markViewed(ready.moment.id, now())
            viewedMomentId = ready.moment.id
        }
        mutableState.value = ready.copy(isPlaying = true)
    }

    private fun apply(result: TodayAssetResult) {
        mutableState.value = when (result) {
            is TodayAssetResult.Playable -> TodayUiState.Ready(result.moment, isPlaying = false)
            is TodayAssetResult.AssetMissing -> TodayUiState.AssetMissing(result.momentId)
            is TodayAssetResult.Retryable -> TodayUiState.RetryableError(
                result.momentId,
                result.error.toUiCode(),
            )
        }
    }
}

private fun AssetError.toUiCode(): TodayErrorCode = when (this) {
    AssetError.PermissionTemporarilyUnavailable,
    AssetError.ProviderTemporarilyUnavailable,
    -> TodayErrorCode.AssetTemporarilyUnavailable
}
