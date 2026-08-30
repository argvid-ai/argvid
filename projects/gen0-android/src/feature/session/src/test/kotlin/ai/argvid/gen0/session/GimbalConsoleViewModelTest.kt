package ai.argvid.gen0.session

import ai.argvid.gen0.domain.gimbal.CommandReceipt
import ai.argvid.gen0.domain.gimbal.EStopReason
import ai.argvid.gen0.domain.gimbal.GimbalCandidate
import ai.argvid.gen0.domain.gimbal.GimbalCapability
import ai.argvid.gen0.domain.gimbal.GimbalCommandError
import ai.argvid.gen0.domain.gimbal.GimbalCommandException
import ai.argvid.gen0.domain.gimbal.GimbalConnectionState
import ai.argvid.gen0.domain.gimbal.GimbalController
import ai.argvid.gen0.domain.gimbal.GimbalDeviceId
import ai.argvid.gen0.domain.gimbal.GimbalEvent
import ai.argvid.gen0.domain.gimbal.GimbalLink
import ai.argvid.gen0.domain.gimbal.GimbalMode
import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.gimbal.GimbalTelemetry
import ai.argvid.gen0.domain.gimbal.SemanticSetpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GimbalConsoleViewModelTest {
    @Test
    fun homeIsDisabledUntilCapabilityIsAvailable() = runTest {
        val link = ConsoleTestLink()
        val viewModel = GimbalConsoleViewModel(GimbalController(link), backgroundScope)
        runCurrent()

        assertFalse(viewModel.uiState.value.homeEnabled)
        viewModel.onAction(GimbalConsoleAction.Connect(GimbalDeviceId("sim-1")))
        runCurrent()

        assertTrue(viewModel.uiState.value.homeEnabled)
    }

    @Test
    fun emergencyStopRemainsEnabledInEveryConnectedState() = runTest {
        val link = ConsoleTestLink()
        val viewModel = GimbalConsoleViewModel(GimbalController(link), backgroundScope)

        link.connection.value = GimbalConnectionState.Connecting
        runCurrent()
        assertTrue(viewModel.uiState.value.emergencyStopEnabled)

        link.connection.value = GimbalConnectionState.Ready
        runCurrent()
        assertTrue(viewModel.uiState.value.emergencyStopEnabled)
    }

    @Test
    fun commandFailureIsVisibleWithoutRawBluetoothStatus() = runTest {
        val link = ConsoleTestLink(rejectSetpoint = true)
        val viewModel = GimbalConsoleViewModel(GimbalController(link), backgroundScope)
        viewModel.onAction(GimbalConsoleAction.Connect(GimbalDeviceId("sim-1")))
        runCurrent()

        viewModel.onAction(GimbalConsoleAction.Nudge(panDeltaDeg = 5.0, tiltDeltaDeg = 0.0))
        runCurrent()

        val message = viewModel.uiState.value.message.orEmpty()
        assertTrue(message.contains("not ready", ignoreCase = true))
        assertFalse(message.contains("GATT", ignoreCase = true))
        assertFalse(message.contains("133"))
    }

    private class ConsoleTestLink(
        private val rejectSetpoint: Boolean = false,
    ) : GimbalLink {
        override val connection = MutableStateFlow(GimbalConnectionState.Disconnected)
        override val motion = MutableStateFlow(GimbalMotionState.Idle)
        override val telemetry = MutableStateFlow(GimbalTelemetry())
        override val events: Flow<GimbalEvent> = MutableSharedFlow()

        override suspend fun scan() = listOf(GimbalCandidate(GimbalDeviceId("sim-1"), "Simulator", true))

        override suspend fun connect(id: GimbalDeviceId): GimbalCapability {
            connection.value = GimbalConnectionState.Ready
            return GimbalCapability.gen05Simulator()
        }

        override suspend fun send(setpoint: SemanticSetpoint): CommandReceipt {
            if (rejectSetpoint) throw GimbalCommandException(GimbalCommandError.NotReady)
            telemetry.value = telemetry.value.copy(
                panDeg = setpoint.panDeg,
                tiltDeg = setpoint.tiltDeg,
                lastAckSeq = setpoint.seq,
            )
            return CommandReceipt(setpoint.seq, 0)
        }

        override suspend fun setMode(mode: GimbalMode) = CommandReceipt(1u.toUShort(), 0)
        override suspend fun emergencyStop(reason: EStopReason) = CommandReceipt(1u.toUShort(), 0)

        override suspend fun disconnect() {
            connection.value = GimbalConnectionState.Disconnected
        }
    }
}
