package ai.argvid.gen0.today

import ai.argvid.gen0.media.catalog.TodayMoment

enum class TodayErrorCode {
    AssetTemporarilyUnavailable,
}

sealed interface TodayUiState {
    data object Empty : TodayUiState
    data class Ready(val moment: TodayMoment, val isPlaying: Boolean) : TodayUiState
    data class AssetMissing(val momentId: String) : TodayUiState
    data class RetryableError(val momentId: String, val code: TodayErrorCode) : TodayUiState
}

sealed interface DeletionUiState {
    data object None : DeletionUiState
    data class Confirm(val momentId: String) : DeletionUiState
    data object Deleting : DeletionUiState
    data object Complete : DeletionUiState
    data object RetryRequired : DeletionUiState
    data object RecordCleared : DeletionUiState
}
