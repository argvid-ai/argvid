package ai.argvid.gen0.domain.gimbal

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GimbalControllerTest {
    @Test
    fun unsupportedTiltIsRejectedWithoutSending() = runTest {
        val link = RecordingGimbalLink(capability = panOnlyCapability)
        val controller = GimbalController(link)
        controller.connect(GimbalDeviceId("test"))

        val result = controller.nudge(panDeltaDeg = 0.0, tiltDeltaDeg = 5.0)

        assertEquals(CommandResult.Rejected(CommandFailure.TiltUnsupported), result)
        assertTrue(link.sentSetpoints.isEmpty())
    }

    @Test
    fun mismatchedAckIsNotReportedAsAccepted() = runTest {
        val link = RecordingGimbalLink(capability = fullCapability, ackOffset = 1)
        val controller = GimbalController(link)
        controller.connect(GimbalDeviceId("test"))

        val result = controller.nudge(panDeltaDeg = 5.0, tiltDeltaDeg = 0.0)

        assertEquals(
            CommandResult.Rejected(CommandFailure.AckMismatch(expected = 1u.toUShort(), actual = 2u.toUShort())),
            result,
        )
    }

    @Test
    fun commandTimesOutWithoutAnAcknowledgement() = runTest {
        val link = RecordingGimbalLink(capability = fullCapability, neverAcknowledge = true)
        val controller = GimbalController(link, commandTimeoutMs = 400)
        controller.connect(GimbalDeviceId("test"))

        assertEquals(CommandResult.TimedOut, controller.nudge(5.0, 0.0))
    }

    @Test
    fun sequenceWrapsFromUShortMaxToZero() = runTest {
        val link = RecordingGimbalLink(capability = fullCapability)
        val controller = GimbalController(link, initialSeq = UShort.MAX_VALUE)
        controller.connect(GimbalDeviceId("test"))

        val result = controller.nudge(5.0, 0.0)

        assertEquals(0u.toUShort(), link.sentSetpoints.single().seq)
        assertEquals(CommandResult.Accepted(CommandReceipt(0u.toUShort(), 0)), result)
    }

    @Test
    fun emergencyStopDoesNotWaitForCapabilityHandshake() = runTest {
        val link = RecordingGimbalLink(capability = fullCapability)
        link.connection.value = GimbalConnectionState.Connecting
        val controller = GimbalController(link)

        val result = controller.emergencyStop()

        assertEquals(CommandResult.Accepted(CommandReceipt(1u.toUShort(), 0)), result)
        assertEquals(1, link.emergencyStopCount)
    }

    private class RecordingGimbalLink(
        private val capability: GimbalCapability,
        private val ackOffset: Int = 0,
        private val neverAcknowledge: Boolean = false,
    ) : GimbalLink {
        override val connection = MutableStateFlow(GimbalConnectionState.Disconnected)
        override val motion = MutableStateFlow(GimbalMotionState.Idle)
        override val telemetry = MutableStateFlow(GimbalTelemetry())
        override val events: Flow<GimbalEvent> = MutableSharedFlow()
        val sentSetpoints = mutableListOf<SemanticSetpoint>()
        var emergencyStopCount = 0

        override suspend fun scan() = listOf(GimbalCandidate(GimbalDeviceId("test"), "Test", true))

        override suspend fun connect(id: GimbalDeviceId): GimbalCapability {
            connection.value = GimbalConnectionState.Ready
            return capability
        }

        override suspend fun send(setpoint: SemanticSetpoint): CommandReceipt {
            sentSetpoints += setpoint
            if (neverAcknowledge) awaitCancellation()
            val ack = (setpoint.seq.toInt() + ackOffset).toUShort()
            return CommandReceipt(ack, acceptedAtUs = 0)
        }

        override suspend fun setMode(mode: GimbalMode) = CommandReceipt(1u.toUShort(), 0)
        override suspend fun emergencyStop(reason: EStopReason): CommandReceipt {
            emergencyStopCount++
            return CommandReceipt(1u.toUShort(), 0)
        }

        override suspend fun disconnect() {
            connection.value = GimbalConnectionState.Disconnected
        }
    }

    companion object {
        private val fullCapability = GimbalCapability.gen05Simulator()
        private val panOnlyCapability = fullCapability.copy(tiltRangeDeg = null)
    }
}
