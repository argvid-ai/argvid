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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

interface TodaySource {
    val moments: Flow<List<TodayMoment>>
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
    override val moments = repository.moments
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
    private val mutableMoments = MutableStateFlow<List<TodayMoment>>(emptyList())
    private var selectedMoment: TodayMoment? = null
    private var viewedMomentId: String? = null
    private var deletingMomentId: String? = null
    private var playingMomentId: String? = null
    private var refreshJob: Job? = null
    private var refreshVersion = 0L
    private var libraryVersion = 0L
    private var clearingRecord = false
    val state: StateFlow<TodayUiState> = mutableState.asStateFlow()
    val deletionState: StateFlow<DeletionUiState> = mutableDeletionState.asStateFlow()
    val moments: StateFlow<List<TodayMoment>> = mutableMoments.asStateFlow()

    init {
        scope.launch {
            source.moments.collect { moments ->
                updateLibrary(moments)
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
        playingMomentId = ready.moment.id
        player.play(ready.moment.mediaUri)
    }

    fun retry() {
        updateSelection(selectedMoment)
    }

    fun refreshLibrary() {
        val version = libraryVersion
        scope.launch {
            val moments = source.moments.first()
            if (version == libraryVersion) updateLibrary(moments)
        }
    }

    fun selectMoment(momentId: String) {
        val moment = mutableMoments.value.find { it.id == momentId } ?: return
        updateSelection(moment)
    }

    private fun updateLibrary(moments: List<TodayMoment>) {
        ++libraryVersion
        mutableMoments.value = moments
        updateSelection(moments.find { it.id == selectedMoment?.id } ?: moments.firstOrNull())
    }

    private fun updateSelection(moment: TodayMoment?) {
        refreshJob?.cancel()
        val version = ++refreshVersion
        val ready = mutableState.value as? TodayUiState.Ready
        if (ready != null &&
            (ready.moment.id != moment?.id || ready.moment.mediaUri != moment.mediaUri)) {
            if (playingMomentId != null) onStop()
            mutableState.value = TodayUiState.Empty
        }
        selectedMoment = moment
        if (moment == null) {
            if (mutableState.value !is TodayUiState.AssetMissing) mutableState.value = TodayUiState.Empty
            return
        }
        if (mutableState.value !is TodayUiState.Ready) {
            mutableState.value = TodayUiState.Loading(moment.id)
        }
        refreshJob = scope.launch {
            val result = source.refresh(moment.id)
            // A slow provider response cannot restore an older video's controls.
            if (version == refreshVersion) apply(result)
        }
    }

    fun requestLocalDeletion() {
        val ready = mutableState.value as? TodayUiState.Ready ?: return
        if (deletion == null || clearingRecord || !mutableDeletionState.value.canRequestDeletion) return
        deletingMomentId = ready.moment.id
        mutableDeletionState.value = DeletionUiState.Confirm(ready.moment.id, ready.moment.createdAt)
    }

    fun dismissLocalDeletion() {
        if (mutableDeletionState.value is DeletionUiState.Confirm) {
            mutableDeletionState.value = DeletionUiState.None
            deletingMomentId = null
        }
    }

    fun confirmLocalDeletion() {
        val confirmation = mutableDeletionState.value as? DeletionUiState.Confirm ?: return
        deletingMomentId = confirmation.momentId
        performLocalDeletion(confirmation.momentId)
    }

    fun retryLocalDeletion() {
        if (mutableDeletionState.value !is DeletionUiState.RetryRequired) return
        val momentId = deletingMomentId ?: return
        performLocalDeletion(momentId)
    }

    fun clearLocalRecord() {
        if (mutableDeletionState.value !is DeletionUiState.Complete || clearingRecord) return
        val momentId = deletingMomentId ?: return
        val deleter = deletion ?: return
        clearingRecord = true
        scope.launch {
            try {
                if (deleter.clearRecord(momentId)) {
                    mutableDeletionState.value = DeletionUiState.RecordCleared(momentId)
                    deletingMomentId = null
                }
            } finally {
                clearingRecord = false
            }
        }
    }

    private fun performLocalDeletion(momentId: String) {
        val deleter = deletion ?: return
        onStop()
        refreshJob?.cancel()
        ++refreshVersion
        mutableDeletionState.value = DeletionUiState.Deleting(momentId)
        scope.launch {
            mutableDeletionState.value = when (deleter.delete(momentId)) {
                LocalDeletionUiResult.Complete -> DeletionUiState.Complete(momentId)
                LocalDeletionUiResult.RetryRequired -> DeletionUiState.RetryRequired(momentId)
            }
        }
    }

    fun onStop() {
        player.release()
        playingMomentId = null
        val ready = mutableState.value as? TodayUiState.Ready ?: return
        mutableState.value = ready.copy(isPlaying = false)
    }

    private suspend fun onFirstFrameRendered() {
        val ready = mutableState.value as? TodayUiState.Ready ?: return
        if (playingMomentId != ready.moment.id) return
        mutableState.value = ready.copy(isPlaying = true)
        if (viewedMomentId != ready.moment.id) {
            viewedMomentId = ready.moment.id
            source.markViewed(ready.moment.id, now())
        }
    }

    private fun apply(result: TodayAssetResult) {
        val current = mutableState.value as? TodayUiState.Ready
        val sameAsset = result is TodayAssetResult.Playable &&
            current?.moment?.id == result.moment.id && current.moment.mediaUri == result.moment.mediaUri
        if (!sameAsset && playingMomentId != null) onStop()
        mutableState.value = when (result) {
            is TodayAssetResult.Playable -> TodayUiState.Ready(
                result.moment, isPlaying = sameAsset && current.isPlaying,
            )
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
