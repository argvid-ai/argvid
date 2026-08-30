package ai.argvid.gen0.session

import ai.argvid.gen0.domain.gimbal.GimbalConnectionState
import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.session.SessionState

data class GimbalUiState(
    val connection: GimbalConnectionState = GimbalConnectionState.Disconnected,
    val motion: GimbalMotionState = GimbalMotionState.Idle,
    val temperatureC: Double = 0.0,
)

data class SessionUiState(
    val sessionState: SessionState = SessionState.Idle,
    val previewVisible: Boolean = false,
    val effectiveDurationUs: Long = 0,
    val proxyProfile: String = "960×540 · 8 fps · JPEG 70%",
    val gimbal: GimbalUiState = GimbalUiState(),
    val warmupRemainingUs: Long = 15_000_000,
    val rescueEnabled: Boolean = false,
    val stopEnabled: Boolean = false,
    val statusText: String = "准备开始前台会话",
    val showSaved: Boolean = false,
    val showSaveFailure: Boolean = false,
    val showCleanupFailure: Boolean = false,
    val permissionRequest: AppPermission? = null,
    val resumeConfirmationRequired: Boolean = false,
)

sealed interface SessionAction {
    data object StartPreflight : SessionAction
    data object ConnectGimbal : SessionAction
    data object Rescue : SessionAction
    data object Stop : SessionAction
    data object RetrySave : SessionAction
    data object AbandonSave : SessionAction
    data object RetryCleanup : SessionAction
    data object ConfirmResume : SessionAction
}
