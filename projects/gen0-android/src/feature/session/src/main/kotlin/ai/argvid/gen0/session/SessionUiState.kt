package ai.argvid.gen0.session

import ai.argvid.gen0.domain.detection.AutomaticRecordingDecision
import ai.argvid.gen0.domain.detection.DetectionSensitivity
import ai.argvid.gen0.domain.detection.SubjectLabel
import ai.argvid.gen0.domain.gimbal.GimbalConnectionState
import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.session.SessionState

data class GimbalUiState(
    val connection: GimbalConnectionState = GimbalConnectionState.Disconnected,
    val motion: GimbalMotionState = GimbalMotionState.Idle,
    val temperatureC: Double = 0.0,
    val source: GimbalSource = GimbalSource.Simulator,
)

data class GimbalDiscoveryUiState(
    val scanning: Boolean = false,
    val candidates: List<String> = emptyList(),
    val error: String? = null,
)

enum class GimbalSource {
    Simulator,
    RealBle,
}

data class SubjectDetectionUiState(
    val trackingEnabled: Boolean = false,
    val trackingGain: Double = DEFAULT_TRACKING_GAIN,
    val invertPan: Boolean = false,
    val invertTilt: Boolean = false,
    val detectorAvailable: Boolean = false,
    val labels: Set<SubjectLabel> = emptySet(),
    val personSensitivity: DetectionSensitivity = DetectionSensitivity.DEFAULT,
    val faceSensitivity: DetectionSensitivity = DetectionSensitivity.DEFAULT,
    val lastDecision: AutomaticRecordingDecision? = null,
)

data class SessionUiState(
    val sessionState: SessionState = SessionState.Idle,
    val previewVisible: Boolean = false,
    val effectiveDurationUs: Long = 0,
    val proxyProfile: String = "960×540 · 8 fps · JPEG 70%",
    val gimbal: GimbalUiState = GimbalUiState(),
    val gimbalDiscovery: GimbalDiscoveryUiState = GimbalDiscoveryUiState(),
    val warmupRemainingUs: Long = 15_000_000,
    val rescueEnabled: Boolean = false,
    val stopEnabled: Boolean = false,
    val statusText: String = "准备开始前台会话",
    val showSaved: Boolean = false,
    val showSaveFailure: Boolean = false,
    val showCatalogFailure: Boolean = false,
    val showCleanupFailure: Boolean = false,
    val permissionRequest: AppPermission? = null,
    val resumeConfirmationRequired: Boolean = false,
    val gimbalNotice: String? = null,
    val subjectDetection: SubjectDetectionUiState = SubjectDetectionUiState(),
)

sealed interface SessionAction {
    data object StartPreflight : SessionAction
    data object ConnectGimbal : SessionAction
    data class SelectGimbalSource(val source: GimbalSource) : SessionAction
    data object ScanRealGimbal : SessionAction
    data class SetTrackingEnabled(val enabled: Boolean) : SessionAction
    data class SetTrackingAxisInversion(val invertPan: Boolean, val invertTilt: Boolean) : SessionAction
    data class SetTrackingGain(val gain: Double) : SessionAction
    data object Rescue : SessionAction
    data object Stop : SessionAction
    data object RetrySave : SessionAction
    data object AbandonSave : SessionAction
    data object RetryCleanup : SessionAction
    data object ConfirmResume : SessionAction
}
