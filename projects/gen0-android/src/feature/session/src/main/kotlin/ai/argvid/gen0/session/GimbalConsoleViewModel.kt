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
    data object Home : GimbalConsoleAction
    data object Hold : GimbalConsoleAction
    data object EmergencyStop : GimbalConsoleAction
    data object Disconnect : GimbalConsoleAction
}

class GimbalConsoleViewModel(
    private val controller: GimbalController,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val actionScope = scope ?: viewModelScope
    private val currentCapability = MutableStateFlow<GimbalCapability?>(null)
    private val mutableUiState = MutableStateFlow(GimbalConsoleUiState())
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
                    is GimbalEvent.EmergencyStopped -> showMessage("Emergency stop latched.")
                    GimbalEvent.LostContactHold -> showMessage("Lost contact; gimbal is holding.")
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
                                message = if (candidates.isEmpty()) "No gimbal found." else "${candidates.size} simulator found.",
                            )
                        }
                    }

                    is GimbalConsoleAction.Connect -> {
                        currentCapability.value = controller.connect(action.id)
                        showMessage("Simulator connected.")
                    }

                    is GimbalConsoleAction.Nudge -> showResult(
                        controller.nudge(action.panDeltaDeg, action.tiltDeltaDeg),
                    )

                    GimbalConsoleAction.Home -> showResult(controller.home())
                    GimbalConsoleAction.Hold -> showResult(controller.hold())
                    GimbalConsoleAction.EmergencyStop -> showResult(controller.emergencyStop())
                    GimbalConsoleAction.Disconnect -> {
                        controller.disconnect()
                        currentCapability.value = null
                        showMessage("Gimbal disconnected.")
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                showMessage("The gimbal action could not be completed.")
            } finally {
                mutableUiState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun showResult(result: CommandResult) {
        when (result) {
            is CommandResult.Accepted -> showMessage("Command acknowledged #${result.receipt.ackSeq}.")
            is CommandResult.Rejected -> showMessage(result.reason.userMessage())
            CommandResult.TimedOut -> showMessage("Command timed out; gimbal state is unchanged.")
        }
    }

    private fun showMessage(message: String) {
        mutableUiState.update { it.copy(message = message) }
    }
}

private fun CommandFailure.userMessage(): String = when (this) {
    CommandFailure.CapabilityUnavailable -> "Connect a gimbal first."
    CommandFailure.TiltUnsupported -> "This gimbal does not support tilt."
    CommandFailure.PanOutOfRange -> "Pan request is outside the safe range."
    CommandFailure.TiltOutOfRange -> "Tilt request is outside the safe range."
    is CommandFailure.AckMismatch -> "Acknowledgement did not match the command."
    is CommandFailure.LinkRejected -> when (reason) {
        GimbalCommandError.NotReady -> "Gimbal is not ready."
        GimbalCommandError.DeviceNotFound -> "The selected gimbal is unavailable."
        GimbalCommandError.PanOutOfRange -> "Pan request is outside the safe range."
        GimbalCommandError.TiltOutOfRange -> "Tilt request is outside the safe range."
        GimbalCommandError.SpeedOutOfRange -> "Requested motion is too fast."
        GimbalCommandError.DeadlineInvalid -> "Command deadline is unsafe."
        GimbalCommandError.UnsupportedMode -> "This gimbal does not support that mode."
    }
}
