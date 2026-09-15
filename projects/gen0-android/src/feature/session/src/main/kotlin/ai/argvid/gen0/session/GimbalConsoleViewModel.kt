package ai.argvid.gen0.session

import ai.argvid.gen0.domain.gimbal.CommandFailure
import ai.argvid.gen0.domain.gimbal.CommandResult
import ai.argvid.gen0.domain.gimbal.GimbalCandidate
import ai.argvid.gen0.domain.gimbal.GimbalCapability
import ai.argvid.gen0.domain.gimbal.GimbalCommandError
import ai.argvid.gen0.domain.gimbal.GimbalConnectionState
import ai.argvid.gen0.domain.gimbal.GimbalController
import ai.argvid.gen0.domain.gimbal.GimbalDeviceId
import ai.argvid.gen0.domain.gimbal.GimbalEvent
import ai.argvid.gen0.domain.gimbal.GimbalMode
import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.gimbal.GimbalTelemetry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GimbalConsoleUiState(
    val candidates: List<GimbalCandidate> = emptyList(),
    val capability: GimbalCapability? = null,
    val connection: GimbalConnectionState = GimbalConnectionState.Disconnected,
    val motion: GimbalMotionState = GimbalMotionState.Idle,
    val telemetry: GimbalTelemetry = GimbalTelemetry(),
    val isSimulator: Boolean = true,
    val stepDeg: Int = DEFAULT_STEP_DEG,
    val speedRpm: Int = DEFAULT_SPEED_RPM,
    val isBusy: Boolean = false,
    val message: String? = null,
) {
    val homeEnabled: Boolean
        get() = connection == GimbalConnectionState.Ready && capability?.supportedModes?.contains(GimbalMode.Home) == true

    val holdEnabled: Boolean
        get() = connection == GimbalConnectionState.Ready && capability?.supportedModes?.contains(GimbalMode.Hold) == true

    val nudgeEnabled: Boolean
        get() = connection == GimbalConnectionState.Ready && capability != null

    val emergencyStopEnabled: Boolean
        get() = connection == GimbalConnectionState.Connecting || connection == GimbalConnectionState.Ready
}

sealed interface GimbalConsoleAction {
    data object Scan : GimbalConsoleAction
    data class Connect(val id: GimbalDeviceId) : GimbalConsoleAction
    data class Nudge(val panDeltaDeg: Double, val tiltDeltaDeg: Double) : GimbalConsoleAction
    data class MoveTo(val panDeg: Double, val tiltDeg: Double) : GimbalConsoleAction
    data class SetStepDeg(val stepDeg: Int) : GimbalConsoleAction
    data class SetSpeed(val rpm: Int) : GimbalConsoleAction
    data object Home : GimbalConsoleAction
    data object Hold : GimbalConsoleAction
    data object EmergencyStop : GimbalConsoleAction
    data object Disconnect : GimbalConsoleAction
}

class GimbalConsoleViewModel(
    private val controller: GimbalController,
    scope: CoroutineScope? = null,
    isSimulator: Boolean = true,
) : ViewModel() {
    private val actionScope = scope ?: viewModelScope
    private val currentCapability = MutableStateFlow<GimbalCapability?>(null)
    private val mutableUiState = MutableStateFlow(GimbalConsoleUiState(isSimulator = isSimulator))
    val uiState: StateFlow<GimbalConsoleUiState> = mutableUiState.asStateFlow()

    init {
        actionScope.launch {
            combine(
                controller.link.connection,
                controller.link.motion,
                controller.link.telemetry,
                currentCapability,
            ) { connection, motion, telemetry, capability ->
                mutableUiState.value.copy(
                    capability = capability,
                    connection = connection,
                    motion = motion,
                    telemetry = telemetry,
                )
            }.collect { mutableUiState.value = it }
        }
        actionScope.launch {
            controller.link.events.collect { event ->
                when (event) {
                    is GimbalEvent.EmergencyStopped -> showMessage("紧急停止已锁存。")
                    GimbalEvent.LostContactHold -> showMessage("连接丢失；云台保持当前位置。")
                    is GimbalEvent.CommandRejected -> Unit
                    is GimbalEvent.MotionChanged -> Unit
                }
            }
        }
    }

    fun onAction(action: GimbalConsoleAction) {
        actionScope.launch {
            mutableUiState.update { it.copy(isBusy = true, message = null) }
            try {
                when (action) {
                    GimbalConsoleAction.Scan -> {
                        val candidates = controller.scan()
                        mutableUiState.update {
                            it.copy(
                                candidates = candidates,
                                message = if (candidates.isEmpty()) "未发现云台。" else "发现 ${candidates.size} 台云台。",
                            )
                        }
                    }

                    is GimbalConsoleAction.Connect -> {
                        currentCapability.value = controller.connect(action.id)
                        showMessage("云台已连接。")
                    }

                    is GimbalConsoleAction.Nudge -> showResult(
                        controller.nudge(action.panDeltaDeg, action.tiltDeltaDeg),
                    )

                    is GimbalConsoleAction.MoveTo -> showResult(
                        controller.moveTo(action.panDeg, action.tiltDeg),
                    )

                    is GimbalConsoleAction.SetStepDeg -> mutableUiState.update { it.copy(stepDeg = action.stepDeg) }

                    is GimbalConsoleAction.SetSpeed -> {
                        mutableUiState.update { it.copy(speedRpm = action.rpm) }
                        showResult(controller.setSpeed(action.rpm))
                    }

                    GimbalConsoleAction.Home -> showResult(controller.home())
                    GimbalConsoleAction.Hold -> showResult(controller.hold())
                    GimbalConsoleAction.EmergencyStop -> showResult(controller.emergencyStop())
                    GimbalConsoleAction.Disconnect -> {
                        controller.disconnect()
                        currentCapability.value = null
                        showMessage("云台已断开。")
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                showMessage("云台操作未能完成。")
            } finally {
                mutableUiState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun showResult(result: CommandResult) {
        when (result) {
            is CommandResult.Accepted -> showMessage("命令已应答 #${result.receipt.ackSeq}。")
            is CommandResult.Rejected -> showMessage(result.reason.userMessage())
            CommandResult.TimedOut -> showMessage("命令超时；云台状态未改变。")
        }
    }

    private fun showMessage(message: String) {
        mutableUiState.update { it.copy(message = message) }
    }
}

private fun CommandFailure.userMessage(): String = when (this) {
    CommandFailure.CapabilityUnavailable -> "请先连接云台。"
    CommandFailure.TiltUnsupported -> "该云台不支持俯仰。"
    CommandFailure.PanOutOfRange -> "水平请求超出安全范围。"
    CommandFailure.TiltOutOfRange -> "俯仰请求超出安全范围。"
    CommandFailure.TelemetryStale -> "实测位置已过期，等待新遥测后再移动。"
    is CommandFailure.AckMismatch -> "应答与命令不匹配。"
    is CommandFailure.LinkRejected -> when (reason) {
        GimbalCommandError.NotReady -> "云台未就绪。"
        GimbalCommandError.DeviceNotFound -> "所选云台不可用。"
        GimbalCommandError.PanOutOfRange -> "水平请求超出安全范围。"
        GimbalCommandError.TiltOutOfRange -> "俯仰请求超出安全范围。"
        GimbalCommandError.SpeedOutOfRange -> "请求速度过快。"
        GimbalCommandError.DeadlineInvalid -> "命令期限不安全。"
        GimbalCommandError.UnsupportedMode -> "该云台不支持此模式。"
    }
}

private const val DEFAULT_STEP_DEG = 5
private const val DEFAULT_SPEED_RPM = 60
