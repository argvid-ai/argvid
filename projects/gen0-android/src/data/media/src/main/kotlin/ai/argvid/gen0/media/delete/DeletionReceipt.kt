package ai.argvid.gen0.media.delete

enum class LocalDeletionState {
    COMPLETE,
    RETRY_REQUIRED,
    NOT_FOUND,
}

data class DeletionReceipt(
    val momentId: String,
    val localState: LocalDeletionState,
    val localDeletedAt: String?,
)
