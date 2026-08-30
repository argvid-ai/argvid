package ai.argvid.gen0.session

import ai.argvid.gen0.domain.capture.CaptureSessionController
import ai.argvid.gen0.domain.capture.CaptureStopResult
import ai.argvid.gen0.domain.capture.StopReason
import ai.argvid.gen0.domain.gimbal.GimbalConnectionState
import ai.argvid.gen0.domain.gimbal.GimbalController
import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.gimbal.GimbalTelemetry
import ai.argvid.gen0.domain.moment.MomentCoordinator
import ai.argvid.gen0.domain.moment.MomentResult
import ai.argvid.gen0.domain.moment.MomentFailure
import ai.argvid.gen0.domain.moment.MomentState
import ai.argvid.gen0.domain.session.PauseReason
import ai.argvid.gen0.domain.session.SessionState
import ai.argvid.gen0.domain.time.MonotonicClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

interface SessionCaptureActions {
    val state: StateFlow<SessionState>
    val rescueAvailable: StateFlow<Boolean>
    val acceptFrames: StateFlow<Boolean>
    suspend fun beginSession()
    suspend fun onMotion(next: GimbalMotionState)
    suspend fun stop(reason: StopReason): CaptureStopResult
}

class DomainSessionCapture(
    private val controller: CaptureSessionController,
) : SessionCaptureActions {
    override val state = controller.state
    override val rescueAvailable = controller.rescueAvailable
    override val acceptFrames = controller.acceptFrames
    override suspend fun beginSession() = controller.beginSession()
    override suspend fun onMotion(next: GimbalMotionState) = controller.onMotion(next)
    override suspend fun stop(reason: StopReason) = controller.stop(reason)
}

interface SessionMomentActions {
    val state: StateFlow<MomentState>
    suspend fun beginSession() {}
    suspend fun captureRescue(nowUs: Long): MomentResult
    suspend fun retrySaving(): MomentResult
    suspend fun abandon(): MomentResult
    suspend fun retryCleanup(): MomentResult
    fun onStop()
}

class DomainSessionMoments(
    private val coordinator: MomentCoordinator,
) : SessionMomentActions {
    override val state = coordinator.state
    override suspend fun beginSession() = coordinator.beginSession()
    override suspend fun captureRescue(nowUs: Long) = coordinator.captureRescue(nowUs)
    override suspend fun retrySaving() = coordinator.retrySaving()
    override suspend fun abandon() = coordinator.abandon()
    override suspend fun retryCleanup() = coordinator.retryCleanup()
    override fun onStop() = coordinator.onStop()
}

interface SessionGimbalStatus {
    val connection: StateFlow<GimbalConnectionState>
    val motion: StateFlow<GimbalMotionState>
    val telemetry: StateFlow<GimbalTelemetry>
}

class DomainSessionGimbal(controller: GimbalController) : SessionGimbalStatus {
    override val connection = controller.link.connection
    override val motion = controller.link.motion
    override val telemetry = controller.link.telemetry
}

class SessionViewModel(
    private val capture: SessionCaptureActions,
    private val moments: SessionMomentActions,
    private val gimbal: SessionGimbalStatus,
    private val permissionCoordinator: PermissionCoordinator,
    private val clock: MonotonicClock,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val actionScope = scope ?: viewModelScope
    private var effectiveDurationUs = if (capture.rescueAvailable.value) RESCUE_DURATION_US else 0L
    private var permissionMessage: String? = null
    private var resumeConfirmationRequired = false
    private var stopJob: Job? = null
    private var startJob: Job? = null
    private var cleanupFailed = false
    private val mutableUiState = MutableStateFlow(buildState())
    val uiState: StateFlow<SessionUiState> = mutableUiState.asStateFlow()

    init {
        actionScope.launch {
            combine(capture.state, capture.rescueAvailable, capture.acceptFrames) { _, _, _ -> Unit }
                .collect { refresh() }
        }
        actionScope.launch { moments.state.collect { refresh() } }
        actionScope.launch {
            combine(gimbal.connection, gimbal.motion, gimbal.telemetry) { _, _, _ -> Unit }
                .collect { refresh() }
        }
    }

    fun onAction(action: SessionAction) {
        when (action) {
            SessionAction.StartPreflight -> requestStart()
            SessionAction.ConnectGimbal -> {
                permissionMessage = "仅提供语义模拟器；不连接物理云台"
                refresh()
            }
            SessionAction.Rescue -> actionScope.launch { applyMomentResult(moments.captureRescue(clock.nowUs())) }
            SessionAction.RetrySave -> actionScope.launch { applyMomentResult(moments.retrySaving()) }
            SessionAction.AbandonSave -> actionScope.launch { applyMomentResult(moments.abandon()) }
            SessionAction.RetryCleanup -> actionScope.launch { applyMomentResult(moments.retryCleanup()) }
            SessionAction.Stop -> stop(StopReason.Screen, requireResume = false)
            SessionAction.ConfirmResume -> {
                requestStart()
            }
        }
    }

    fun onMotion(next: GimbalMotionState) {
        actionScope.launch {
            if (next != GimbalMotionState.Idle) effectiveDurationUs = 0
            capture.onMotion(next)
            refresh()
        }
    }

    private fun applyMomentResult(result: MomentResult) {
        if (result.failure == MomentFailure.CleanupFailed) cleanupFailed = true
        else if (result.failure == null || result.failure == MomentFailure.NoPendingMoment) cleanupFailed = false
        refresh()
    }

    fun onWarmupProgress(durationUs: Long) {
        effectiveDurationUs = durationUs.coerceIn(0, RESCUE_DURATION_US)
        refresh()
    }

    fun onPermissionResult(permission: AppPermission, granted: Boolean) {
        if (mutableUiState.value.permissionRequest != permission) return
        permissionCoordinator.resolve(permission, granted)
        permissionMessage = if (granted) {
            "${permission.displayName()}权限已授予"
        } else {
            permission.deniedMessage()
        }
        mutableUiState.value = buildState(permissionRequest = null)
        if (permission == AppPermission.Camera && granted) {
            startJob = actionScope.launch {
                moments.beginSession()
                capture.beginSession()
                resumeConfirmationRequired = false
                permissionMessage = null
                effectiveDurationUs = 0
                refresh()
            }
        }
    }

    fun onAppStopped() {
        if ((capture.state.value == SessionState.Idle || capture.state.value == SessionState.Ended) &&
            mutableUiState.value.permissionRequest == null && startJob?.isActive != true) return
        stop(StopReason.Background, requireResume = true)
    }

    private fun stop(reason: StopReason, requireResume: Boolean) {
        startJob?.cancel()
        permissionCoordinator.cancelPendingRequest()
        moments.onStop()
        resumeConfirmationRequired = requireResume
        permissionMessage = null
        effectiveDurationUs = 0
        mutableUiState.value = buildState(permissionRequest = null)
        stopJob = actionScope.launch {
            capture.stop(reason)
            refresh()
        }
    }

    private fun requestStart() {
        if (capture.state.value == SessionState.Running &&
            permissionCoordinator.status(AppPermission.Camera) == PermissionStatus.Granted) return
        val stopping = stopJob
        if (stopping?.isActive == true) {
            startJob = actionScope.launch { stopping.join(); requestPermission(AppPermission.Camera) }
        } else requestPermission(AppPermission.Camera)
    }

    private fun requestPermission(permission: AppPermission) {
        val request = if (permissionCoordinator.status(permission) == PermissionStatus.Granted) {
            permission // A new binding request; the host reuses the existing grant.
        } else permissionCoordinator.request(permission)
        if (request == null && permissionCoordinator.status(permission) == PermissionStatus.Denied) {
            permissionMessage = permission.deniedMessage()
        }
        mutableUiState.value = buildState(permissionRequest = request)
    }

    private fun refresh() {
        mutableUiState.value = buildState(permissionRequest = mutableUiState.value.permissionRequest)
    }

    private fun buildState(permissionRequest: AppPermission? = null): SessionUiState {
        val session = capture.state.value
        val moment = moments.state.value
        val rescueAvailable = capture.rescueAvailable.value
        if (rescueAvailable) effectiveDurationUs = RESCUE_DURATION_US
        val active = session != SessionState.Idle &&
            session != SessionState.Ended &&
            session != SessionState.Paused(PauseReason.UserStop)
        val status = when {
            moment == MomentState.CatalogFailed -> "视频已在相册；本地记录失败，请重试记录，暂存副本仍保留"
            resumeConfirmationRequired -> "会话已暂停，请确认后重新开始"
            moment is MomentState.Encoding || moment is MomentState.Saving -> "已锁定最近15秒，正在保存"
            cleanupFailed && moment is MomentState.Saved -> "已保存到相册；暂存清理失败，请重试清理或在 Today 删除"
            cleanupFailed -> "暂存清理失败，请重试清理"
            moment is MomentState.Saved -> "已保存到相册"
            moment == MomentState.SaveFailed -> "保存失败，可重试或放弃"
            permissionMessage != null -> permissionMessage.orEmpty()
            session == SessionState.Paused(PauseReason.Motion) && !capture.acceptFrames.value -> "云台调整中"
            session == SessionState.Paused(PauseReason.Motion) -> "正在重新积累15秒缓冲"
            session == SessionState.Paused(PauseReason.UserStop) -> "采集已停止，缓冲已清除"
            rescueAvailable -> "最近15秒可救回"
            else -> "正在准备15秒救回缓冲"
        }
        return SessionUiState(
            sessionState = session,
            previewVisible = active && session != SessionState.Paused(PauseReason.UserStop),
            effectiveDurationUs = effectiveDurationUs,
            gimbal = GimbalUiState(
                connection = gimbal.connection.value,
                motion = gimbal.motion.value,
                temperatureC = gimbal.telemetry.value.temperatureC,
            ),
            warmupRemainingUs = (RESCUE_DURATION_US - effectiveDurationUs).coerceAtLeast(0),
            rescueEnabled = rescueAvailable && session == SessionState.Running && !cleanupFailed &&
                moment !is MomentState.Encoding && moment !is MomentState.Saving &&
                moment != MomentState.SaveFailed && moment != MomentState.CatalogFailed,
            stopEnabled = active && session != SessionState.Ended,
            statusText = status,
            showSaved = moment is MomentState.Saved,
            showSaveFailure = moment == MomentState.SaveFailed,
            showCatalogFailure = moment == MomentState.CatalogFailed,
            showCleanupFailure = cleanupFailed,
            permissionRequest = permissionRequest,
            resumeConfirmationRequired = resumeConfirmationRequired,
        )
    }

    private fun AppPermission.displayName(): String = when (this) {
        AppPermission.Camera -> "相机"
    }

    private fun AppPermission.deniedMessage(): String = when (this) {
        AppPermission.Camera -> "相机权限未授予，采集不可用"
    }

    private companion object {
        const val RESCUE_DURATION_US = 15_000_000L
    }
}
