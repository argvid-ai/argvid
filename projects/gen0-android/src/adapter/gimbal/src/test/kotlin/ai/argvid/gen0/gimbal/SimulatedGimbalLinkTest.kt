package ai.argvid.gen0.gimbal

import ai.argvid.gen0.domain.gimbal.GimbalCommandError
import ai.argvid.gen0.domain.gimbal.GimbalCommandException
import ai.argvid.gen0.domain.gimbal.GimbalDeviceId
import ai.argvid.gen0.domain.gimbal.GimbalMode
import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.gimbal.SemanticSetpoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SimulatedGimbalLinkTest {
    @Test
    fun holdSupersedesQueuedMotionDeadlines() = runTest {
        val scheduler = ManualGimbalScheduler()
        val link = SimulatedGimbalLink(scheduler, scheduler)
        link.connect(GimbalDeviceId("sim-1"))
        link.send(SemanticSetpoint(1u, 5.0, 0.0))
        link.setMode(GimbalMode.Hold)
        assertEquals(GimbalMotionState.Holding, link.motion.value)
        scheduler.advanceBy(100_000)
        assertEquals(GimbalMotionState.Holding, link.motion.value)
        scheduler.advanceBy(200_000)
        assertEquals(GimbalMotionState.Holding, link.motion.value)
    }

    @Test
    fun manualModeSupersedesQueuedMotionDeadlines() = runTest {
        val scheduler = ManualGimbalScheduler()
        val link = SimulatedGimbalLink(scheduler, scheduler)
        link.connect(GimbalDeviceId("sim-1"))
        link.send(SemanticSetpoint(1u, 5.0, 0.0))
        link.setMode(GimbalMode.Manual)
        scheduler.advanceBy(100_000)
        assertEquals(GimbalMotionState.Idle, link.motion.value)
    }

    @Test
    fun commandEmitsMovingSettlingIdleAndAck() = runTest {
        val scheduler = ManualGimbalScheduler()
        val link = SimulatedGimbalLink(clock = scheduler, scheduler = scheduler)

        link.connect(GimbalDeviceId("sim-1"))
        val receipt = link.send(SemanticSetpoint(seq = 1u.toUShort(), panDeg = 5.0, tiltDeg = 0.0))

        assertEquals(1.toUShort(), receipt.ackSeq)
        assertEquals(GimbalMotionState.Moving, link.motion.value)
        scheduler.advanceBy(100_000)
        assertEquals(GimbalMotionState.Settling, link.motion.value)
        scheduler.advanceBy(200_000)
        assertEquals(GimbalMotionState.Idle, link.motion.value)
    }

    @Test
    fun disconnectedAndOutOfBoundsCommandsAreRejectedWithoutClamping() = runTest {
        val scheduler = ManualGimbalScheduler()
        val link = SimulatedGimbalLink(clock = scheduler, scheduler = scheduler)

        assertCommandError(GimbalCommandError.NotReady) {
            link.send(SemanticSetpoint(seq = 1u.toUShort(), panDeg = 5.0, tiltDeg = 0.0))
        }

        link.connect(GimbalDeviceId("sim-1"))
        assertCommandError(GimbalCommandError.PanOutOfRange) {
            link.send(SemanticSetpoint(seq = 2u.toUShort(), panDeg = 100.0, tiltDeg = 0.0))
        }
        assertEquals(GimbalMotionState.Idle, link.motion.value)
    }

    @Test
    fun unsupportedModeIsRejected() = runTest {
        val scheduler = ManualGimbalScheduler()
        val link = SimulatedGimbalLink(clock = scheduler, scheduler = scheduler)
        link.connect(GimbalDeviceId("sim-1"))

        assertCommandError(GimbalCommandError.UnsupportedMode) {
            link.setMode(GimbalMode.Track)
        }
    }

    @Test
    fun watchdogMovesToHoldingAtAdvertisedDeadline() = runTest {
        val scheduler = ManualGimbalScheduler()
        val link = SimulatedGimbalLink(clock = scheduler, scheduler = scheduler)
        val capability = link.connect(GimbalDeviceId("sim-1"))

        scheduler.advanceBy(capability.watchdogMs * 1_000L - 1)
        assertEquals(GimbalMotionState.Idle, link.motion.value)
        scheduler.advanceBy(1)
        assertEquals(GimbalMotionState.Holding, link.motion.value)
    }

    @Test
    fun consoleDriverCanCompleteMotionWithoutWallClockDelay() = runTest {
        val scheduler = ManualGimbalScheduler()
        val link = SimulatedGimbalLink(
            clock = scheduler,
            scheduler = scheduler,
            afterMotionScheduled = { scheduler.advanceBy(300_000) },
        )
        link.connect(GimbalDeviceId("sim-1"))

        link.send(SemanticSetpoint(seq = 1u.toUShort(), panDeg = 5.0, tiltDeg = 0.0))

        assertEquals(GimbalMotionState.Idle, link.motion.value)
        assertEquals(300_000, scheduler.nowUs())
    }

    private suspend fun assertCommandError(
        expected: GimbalCommandError,
        block: suspend () -> Unit,
    ) {
        val error = try {
            block()
            throw AssertionError("Expected GimbalCommandException")
        } catch (error: GimbalCommandException) {
            error
        }
        assertEquals(expected, error.reason)
    }
}
