package ai.argvid.gen0.today

import ai.argvid.gen0.media.catalog.TodayMoment

enum class TodayErrorCode {
    AssetTemporarilyUnavailable,
}

sealed interface TodayUiState {
    data object Empty : TodayUiState
    data class Loading(val momentId: String) : TodayUiState
    data class Ready(val moment: TodayMoment, val isPlaying: Boolean) : TodayUiState
    data class AssetMissing(val momentId: String) : TodayUiState
    data class RetryableError(val momentId: String, val code: TodayErrorCode) : TodayUiState
}

sealed interface DeletionUiState {
    data object None : DeletionUiState
    data class Confirm(val momentId: String, val createdAt: String? = null) : DeletionUiState
    data class Deleting(val momentId: String) : DeletionUiState
    data class Complete(val momentId: String) : DeletionUiState
    data class RetryRequired(val momentId: String) : DeletionUiState
    data class RecordCleared(val momentId: String) : DeletionUiState
}

val DeletionUiState.canRequestDeletion: Boolean
    get() = this == DeletionUiState.None || this is DeletionUiState.Complete ||
        this is DeletionUiState.RecordCleared
