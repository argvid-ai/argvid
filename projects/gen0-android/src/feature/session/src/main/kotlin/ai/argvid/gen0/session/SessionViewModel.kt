package ai.argvid.gen0.session

import ai.argvid.gen0.domain.capture.CaptureSessionController
import ai.argvid.gen0.domain.capture.CaptureStopResult
import ai.argvid.gen0.domain.capture.StopReason
import ai.argvid.gen0.domain.detection.AutomaticRecordingPolicy
import ai.argvid.gen0.domain.detection.DetectionSensitivity
import ai.argvid.gen0.domain.detection.GimbalSubjectTracker
import ai.argvid.gen0.domain.detection.SubjectObservation
import ai.argvid.gen0.domain.detection.TrackingOptics
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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

fun interface GimbalDiscovery {
    suspend fun scan(): List<String>
}

/** External on-device face detection source with its own lifecycle. */
interface FaceDetectionSource {
    val observations: Flow<SubjectObservation>
    fun start(minConfidence: Float, minWidthRatio: Float)
    fun updateThresholds(minConfidence: Float, minWidthRatio: Float)
    fun stop()
}

/** Stops real-gimbal motion for app stop/background teardown. */
interface RealGimbalTeardown {
    /** Bounded hold; true only if accepted, false on rejection/timeout. */
    suspend fun hold(): Boolean

    /** Controlled disconnect — the firmware fail-stops both axes on link loss. */
    suspend fun disconnect(): Boolean
}

/**
 * Applies bounded, measured-angle tracking corrections through the gimbal
 * controller. Tracking must use the controller boundary so capability ranges
 * and telemetry freshness are checked before every physical move.
 */
interface GimbalTrackingDriver {
    suspend fun nudge(panDeltaDeg: Double, tiltDeltaDeg: Double): Boolean

    suspend fun stop(): Boolean
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
    private val discovery: GimbalDiscovery? = null,
    private val detection: FaceDetectionSource? = null,
    private val trackingDriver: GimbalTrackingDriver? = null,
    private val trackingGimbal: SessionGimbalStatus? = null,
    private val realGimbalTeardown: RealGimbalTeardown? = null,
    private val subjectTracker: GimbalSubjectTracker = GimbalSubjectTracker(
        // Phone-camera FOV defaults; axis signs assume an upright camera mount.
        // Calibrate both on the approved device before relying on tracking.
        TrackingOptics(horizontalFovDeg = 60.0, verticalFovDeg = 40.0),
        maxCorrectionDeg = 8.0,
    ),
) : ViewModel() {
    private val actionScope = scope ?: viewModelScope
    private var effectiveDurationUs = if (capture.rescueAvailable.value) RESCUE_DURATION_US else 0L
    private var permissionMessage: String? = null
    private var resumeConfirmationRequired = false
    private var stopJob: Job? = null
    private var startJob: Job? = null
    private var cleanupFailed = false
    private var gimbalNotice: String? = null
    private var gimbalSource = GimbalSource.Simulator
    private var gimbalDiscovery = GimbalDiscoveryUiState()
    private var rescueInProgress = false
    private var rescueFailureMessage: String? = null
    private var captureFailureMessage: String? = null
    private val automaticRecordingPolicy = AutomaticRecordingPolicy()
    private var subjectDetection = SubjectDetectionUiState()
    private var trackingJob: Job? = null
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
                gimbalSource = GimbalSource.Simulator
                gimbalNotice = "仅提供语义模拟器；不连接物理云台"
                refresh()
            }
            is SessionAction.SelectGimbalSource -> selectGimbalSource(action.source)
            SessionAction.ScanRealGimbal -> scanRealGimbal()
            is SessionAction.SetTrackingEnabled -> setTrackingEnabled(action.enabled)
            is SessionAction.SetTrackingGain -> {
                subjectDetection = subjectDetection.copy(trackingGain = action.gain)
                refresh()
            }
            is SessionAction.SetTrackingAxisInversion -> {
                subjectDetection = subjectDetection.copy(
                    invertPan = action.invertPan,
                    invertTilt = action.invertTilt,
                )
                refresh()
            }
            SessionAction.Rescue -> requestRescue()
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
        rescueFailureMessage = when (result.failure) {
            MomentFailure.InsufficientCoverage -> "缓冲不连续或画面已过期，未保存；请等待缓冲就绪后重试"
            MomentFailure.EncodeFailed -> "录像编码失败，未保存；请重试救回"
            MomentFailure.Stopped -> "会话已停止，本次录像未保存"
            else -> null
        }
        refresh()
    }

    private fun selectGimbalSource(source: GimbalSource) {
        gimbalSource = source
        when (source) {
            GimbalSource.Simulator -> {
                if (subjectDetection.trackingEnabled) setTrackingEnabled(false)
                gimbalNotice = "仅提供语义模拟器；不连接物理云台"
                refresh()
            }
            GimbalSource.RealBle -> {
                gimbalNotice = "真实云台模式已选择；当前仅申请蓝牙权限并准备扫描，不发送电机命令"
                if (permissionCoordinator.status(AppPermission.Bluetooth) == PermissionStatus.Granted) {
                    refresh()
                } else {
                    requestPermission(AppPermission.Bluetooth)
                }
            }
        }
    }

    private fun scanRealGimbal() {
        if (gimbalSource != GimbalSource.RealBle) {
            selectGimbalSource(GimbalSource.RealBle)
            return
        }
        if (permissionCoordinator.status(AppPermission.Bluetooth) != PermissionStatus.Granted) {
            selectGimbalSource(GimbalSource.RealBle)
            return
        }
        val scanner = discovery
        if (scanner == null || gimbalDiscovery.scanning) return
        gimbalDiscovery = GimbalDiscoveryUiState(scanning = true)
        gimbalNotice = "正在扫描 F32C-Gimbal 广播；不会发送电机命令"
        refresh()
        actionScope.launch {
            try {
                val candidates = scanner.scan().distinct()
                gimbalDiscovery = GimbalDiscoveryUiState(candidates = candidates)
                gimbalNotice = if (candidates.isEmpty()) {
                    "未发现 F32C-Gimbal；未发送电机命令"
                } else {
                    "发现 ${candidates.size} 个 F32C-Gimbal；仅完成广播扫描，未连接或发送命令"
                }
            } catch (error: Exception) {
                gimbalDiscovery = GimbalDiscoveryUiState(error = error.message ?: "扫描失败")
                gimbalNotice = "真实云台扫描失败；未发送电机命令"
            } finally {
                refresh()
            }
        }
    }

    fun onWarmupProgress(durationUs: Long) {
        effectiveDurationUs = durationUs.coerceIn(0, RESCUE_DURATION_US)
        refresh()
    }

    private fun requestRescue() {
        if (rescueInProgress || !buildState().rescueEnabled) return
        rescueInProgress = true
        rescueFailureMessage = null
        val requestedAtUs = clock.nowUs()
        refresh()
        actionScope.launch {
            try {
                applyMomentResult(moments.captureRescue(requestedAtUs))
            } finally {
                rescueInProgress = false
                refresh()
            }
        }
    }

    fun onSubjectObservation(observation: SubjectObservation) {
        val decision = automaticRecordingPolicy.update(observation.hasSubject, observation.observedAt)
        subjectDetection = subjectDetection.copy(
            detectorAvailable = true,
            labels = observation.labels,
            lastDecision = decision,
        )
        refresh()
    }

    /** Last telemetry snapshot used to derive measured angular speed. */
    private var settleSamplePan = Double.NaN
    private var settleSampleTilt = Double.NaN
    private var settleSampleAtMs = 0L

    /** True when both axes move slower than the settle threshold (deg/s). */
    private fun axesSettled(): Boolean {
        val sample = (trackingGimbal ?: gimbal).telemetry.value
        val atMs = sample.measuredAtMs
        val dtMs = atMs - settleSampleAtMs
        val panSpeed = if (dtMs > 0 && !settleSamplePan.isNaN()) {
            kotlin.math.abs(sample.panDeg - settleSamplePan) * 1000.0 / dtMs
        } else {
            0.0
        }
        val tiltSpeed = if (dtMs > 0 && !settleSampleTilt.isNaN()) {
            kotlin.math.abs(sample.tiltDeg - settleSampleTilt) * 1000.0 / dtMs
        } else {
            0.0
        }
        settleSamplePan = sample.panDeg
        settleSampleTilt = sample.tiltDeg
        settleSampleAtMs = atMs
        return panSpeed < TRACKING_SETTLE_SPEED_DEG_PER_S && tiltSpeed < TRACKING_SETTLE_SPEED_DEG_PER_S
    }

    /**
     * Subject tracking is the explicit opt-in closed loop. Observations only
     * compute corrections into a conflated channel; a dedicated executor always
     * takes the newest correction and issues one bounded nudge anchored on the
     * latest measured angle. Recording stays untouched by this path.
     */
    private fun setTrackingEnabled(enabled: Boolean) {
        if (enabled == subjectDetection.trackingEnabled) return
        val source = detection
        if (enabled && source == null) {
            gimbalNotice = "当前构建没有检测源；主体跟随不可用"
            refresh()
            return
        }
        subjectDetection = if (enabled) {
            subjectDetection.copy(trackingEnabled = true)
        } else {
            subjectDetection.copy(
                trackingEnabled = false,
                detectorAvailable = false,
                labels = emptySet(),
                lastDecision = null,
            )
        }
        if (enabled) {
            if (gimbalSource != GimbalSource.RealBle) selectGimbalSource(GimbalSource.RealBle)
            actionScope.launch { trackingDriver?.stop() }
            val sensitivity = subjectDetection.faceSensitivity
            source!!.start(FACE_MIN_CONFIDENCE, sensitivity.minimumFaceWidthRatio)
            val corrections = Channel<Pair<Double, Double>>(Channel.CONFLATED)
            trackingJob = actionScope.launch {
                launch {
                    source.observations.collect { observation ->
                        onSubjectObservation(observation)
                        // Hard tilt watchdog: any measured tilt beyond the safe
                        // envelope immediately kills tracking regardless of mode.
                        val measuredTilt = (trackingGimbal ?: gimbal).telemetry.value.tiltDeg
                        if (kotlin.math.abs(measuredTilt) > TILT_AUTOSTOP_DEG) {
                            gimbalNotice = "实测俯仰 ${"%.1f".format(measuredTilt)}° 超出安全范围，主体跟随已自动关闭"
                            setTrackingEnabled(false)
                            return@collect
                        }
                        val correction = subjectTracker.update(observation, observation.observedAt)
                        // No correction means hold the last bounded position target;
                        // there is no continuous velocity to coast. Disabling tracking
                        // sends an explicit stop through the controller boundary.
                        val panSign = if (subjectDetection.invertPan) -1.0 else 1.0
                        val tiltSign = if (subjectDetection.invertTilt) -1.0 else 1.0
                        val gain = subjectDetection.trackingGain
                        if (correction != null) {
                            corrections.trySend(
                                correction.panDeg * panSign * gain to correction.tiltDeg * tiltSign * gain,
                            )
                        }
                    }
                }
                launch {
                    var consecutiveFailures = 0
                    for (correction in corrections) {
                        val driver = trackingDriver ?: break
                        // Settle gating against the REAL gimbal's telemetry: vision
                        // latency means a correction issued while the axes still move
                        // piles a fresh error onto a base that has not caught up.
                        if (!axesSettled()) {
                            android.util.Log.i(TRACK_TAG, "correction dropped: axes unsettled")
                            continue
                        }
                        val accepted = driver.nudge(correction.first, correction.second)
                        android.util.Log.i(
                            TRACK_TAG,
                            "nudge pan=${"%.2f".format(correction.first)} tilt=${"%.2f".format(correction.second)} accepted=$accepted",
                        )
                        if (accepted) {
                            consecutiveFailures = 0
                        } else {
                            // A single rejection (e.g. a transient command timeout on a
                            // congested link) must not turn into a stop command that
                            // fights the next correction; only sustained failure stops.
                            consecutiveFailures += 1
                            if (consecutiveFailures >= 3) {
                                gimbalNotice = "跟踪修正连续被拒，主体跟随已停止"
                                android.util.Log.e(TRACK_TAG, "tracking stopped after $consecutiveFailures consecutive rejections")
                                setTrackingEnabled(false)
                                return@launch
                            }
                        }
                    }
                }
            }
            gimbalNotice = "主体跟随已开启：检测驱动云台小幅修正；录像不受影响"
        } else {
            trackingJob?.cancel()
            trackingJob = null
            source?.stop()
            actionScope.launch { trackingDriver?.stop() }
        }
        refresh()
    }

    fun onPersonDetectionSensitivityChanged(progress: Int) {
        subjectDetection = subjectDetection.copy(
            personSensitivity = DetectionSensitivity.fromProgress(progress),
        )
        refresh()
    }

    fun onFaceDetectionSensitivityChanged(progress: Int) {
        subjectDetection = subjectDetection.copy(
            faceSensitivity = DetectionSensitivity.fromProgress(progress),
        )
        if (subjectDetection.trackingEnabled) {
            val sensitivity = subjectDetection.faceSensitivity
            detection?.updateThresholds(FACE_MIN_CONFIDENCE, sensitivity.minimumFaceWidthRatio)
        }
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
        if (!granted) captureFailureMessage = permission.deniedMessage()
        mutableUiState.value = buildState(permissionRequest = null)
        if (permission == AppPermission.Camera && granted) {
            startJob = actionScope.launch {
                moments.beginSession()
                capture.beginSession()
                resumeConfirmationRequired = false
                permissionMessage = null
                rescueFailureMessage = null
                captureFailureMessage = null
                effectiveDurationUs = 0
                refresh()
            }
        }
    }

    fun onSystemPermissionChanged(permission: AppPermission, granted: Boolean) {
        val previous = permissionCoordinator.status(permission)
        permissionCoordinator.synchronize(permission, granted)
        if (permission == AppPermission.Camera && previous == PermissionStatus.Granted && !granted &&
            (isActiveSession() || startJob?.isActive == true)
        ) {
            stop(StopReason.PermissionLost, requireResume = true)
        } else {
            refresh()
        }
    }

    fun onCaptureFailure(message: String) {
        stop(StopReason.Interruption, requireResume = true)
        captureFailureMessage = message
        refresh()
    }

    /**
     * Unified teardown for real control: cancel tracking, stop detection, and
     * hold the real gimbal. Covers capture-stop, app-background and source
     * switches; re-enabling tracking always needs a fresh opt-in.
     */
    private fun teardownRealControl() {
        if (subjectDetection.trackingEnabled) {
            setTrackingEnabled(false)
        }
        actionScope.launch {
            val teardown = realGimbalTeardown ?: return@launch
            val held = runCatching { teardown.hold() }.getOrDefault(false)
            if (!held) {
                // Hold failed or returned false: fall back to a controlled
                // disconnect — the firmware fail-stops both axes on link loss.
                val disconnected = runCatching { teardown.disconnect() }.getOrDefault(false)
                gimbalNotice = if (disconnected) {
                    "停止保持失败，已断开真实云台连接（固件断链停机兜底）"
                } else {
                    "停止保持与断连均失败，真实云台状态未知，请手动检查"
                }
                refresh()
            }
        }
    }

    fun onAppStopped() {
        // Real-control teardown runs regardless of capture state: tracking and the
        // real BLE gimbal can be active while the capture pipeline is idle (the
        // independent gimbal console path), so the early return must not skip it.
        val captureIdle =
            (capture.state.value == SessionState.Idle || capture.state.value == SessionState.Ended) &&
            mutableUiState.value.permissionRequest == null && startJob?.isActive != true
        if (!captureIdle) {
            stop(StopReason.Background, requireResume = true)
        } else {
            teardownRealControl()
        }
    }

    private fun stop(reason: StopReason, requireResume: Boolean) {
        startJob?.cancel()
        permissionCoordinator.cancelPendingRequest()
        teardownRealControl()
        moments.onStop()
        resumeConfirmationRequired = requireResume
        permissionMessage = null
        rescueFailureMessage = null
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
            captureFailureMessage != null -> captureFailureMessage.orEmpty()
            moment == MomentState.CatalogFailed -> "视频已在相册；本地记录失败，请重试记录，暂存副本仍保留"
            resumeConfirmationRequired -> "会话已暂停，请确认后重新开始"
            moment is MomentState.Encoding || moment is MomentState.Saving -> "已锁定最近15秒，正在保存"
            rescueInProgress && rescueFailureMessage == null -> "正在锁定最近15秒，请稍候"
            cleanupFailed && moment is MomentState.Saved -> "已保存到相册；暂存清理失败，请重试清理或在 Today 删除"
            cleanupFailed -> "暂存清理失败，请重试清理"
            moment is MomentState.Saved -> "已保存到相册"
            moment == MomentState.SaveFailed -> "保存失败，可重试或放弃"
            rescueFailureMessage != null -> rescueFailureMessage.orEmpty()
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
                source = gimbalSource,
                connection = gimbal.connection.value,
                motion = gimbal.motion.value,
                temperatureC = gimbal.telemetry.value.temperatureC,
            ),
            gimbalDiscovery = gimbalDiscovery,
            warmupRemainingUs = (RESCUE_DURATION_US - effectiveDurationUs).coerceAtLeast(0),
            rescueEnabled = rescueAvailable && session == SessionState.Running && !rescueInProgress && !cleanupFailed &&
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
            gimbalNotice = gimbalNotice,
            subjectDetection = subjectDetection,
        )
    }

    private fun isActiveSession(): Boolean {
        val session = capture.state.value
        return session != SessionState.Idle &&
            session != SessionState.Ended &&
            session != SessionState.Paused(PauseReason.UserStop)
    }

    private fun AppPermission.displayName(): String = when (this) {
        AppPermission.Camera -> "相机和麦克风"
        AppPermission.Bluetooth -> "蓝牙"
    }

    private fun AppPermission.deniedMessage(): String = when (this) {
        AppPermission.Camera -> "相机或麦克风权限未授予，采集不可用；请在系统设置中允许两项权限"
        AppPermission.Bluetooth -> "蓝牙权限未授予，真实云台扫描不可用；模拟器仍可使用"
    }

    private companion object {
        const val RESCUE_DURATION_US = 15_000_000L
        const val FACE_MIN_CONFIDENCE = 0.5f
    }
}

private const val TRACK_TAG = "SESSION_TRACK"

/** Measured angular speed (deg/s) below which a new tracking correction may issue. */
private const val TRACKING_SETTLE_SPEED_DEG_PER_S = 3.0

/** Measured tilt beyond this envelope auto-disables subject tracking. */
private const val TILT_AUTOSTOP_DEG = 95.0

internal const val DEFAULT_TRACKING_GAIN = 1.0
