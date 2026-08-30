package ai.argvid.gen0.domain.capture

import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.session.PauseReason
import ai.argvid.gen0.domain.session.SessionState
import ai.argvid.gen0.domain.time.MonotonicClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureGateTest {
    @Test
    fun idleSessionOnlyAcceptsFramesAfterStartAndEnablesRescueAfterCoverage() = runTest {
        val buffer = FakeCaptureBuffer().apply { completeCoverage = true }
        val controller = CaptureSessionController(
            sampler = FakeCaptureSampler(),
            buffer = buffer,
            preview = FakeCapturePreview(),
            gimbal = FakeCaptureGimbal(),
            clock = MonotonicClock { testScheduler.currentTime * 1_000 },
            scope = backgroundScope,
            initialState = SessionState.Idle,
        )

        assertFalse(controller.acceptFrames.value)
        controller.beginSession()
        assertEquals(SessionState.Running, controller.state.value)
        assertTrue(controller.acceptFrames.value)

        controller.onCoverageUpdated(nowUs = 15_000_000)
        assertTrue(controller.rescueAvailable.value)
    }

    @Test
    fun motionRequiresSettleAndFullWarmup() = runTest {
        val buffer = FakeCaptureBuffer()
        val controller = controller(buffer = buffer)

        controller.onMotion(GimbalMotionState.Moving)
        assertEquals(SessionState.Paused(PauseReason.Motion), controller.state.value)
        assertFalse(controller.acceptFrames.value)
        assertFalse(controller.rescueAvailable.value)
        assertEquals(1, buffer.wipeCalls)

        controller.onMotion(GimbalMotionState.Idle)
        advanceTimeBy(299)
        runCurrent()
        assertFalse(controller.acceptFrames.value)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(controller.acceptFrames.value)
        assertEquals(SessionState.Paused(PauseReason.Motion), controller.state.value)

        buffer.completeCoverage = false
        controller.onCoverageUpdated(nowUs = 14_000_000)
        assertFalse(controller.rescueAvailable.value)

        buffer.completeCoverage = true
        controller.onCoverageUpdated(nowUs = 15_000_000)
        assertTrue(controller.rescueAvailable.value)
        assertEquals(SessionState.Running, controller.state.value)
    }

    @Test
    fun renewedMotionCancelsPendingSettleAndWipesAgain() = runTest {
        val buffer = FakeCaptureBuffer()
        val controller = controller(buffer = buffer)

        controller.onMotion(GimbalMotionState.Moving)
        controller.onMotion(GimbalMotionState.Idle)
        advanceTimeBy(200)
        controller.onMotion(GimbalMotionState.Settling)
        advanceTimeBy(200)
        runCurrent()

        assertFalse(controller.acceptFrames.value)
        assertEquals(SessionState.Paused(PauseReason.Motion), controller.state.value)
        assertEquals(2, buffer.wipeCalls)
    }

    @Test
    fun unsafeNonIdleStatesPauseAndWipe() = runTest {
        for (motion in listOf(GimbalMotionState.Holding, GimbalMotionState.Stalled, GimbalMotionState.Fault)) {
            val buffer = FakeCaptureBuffer()
            val controller = controller(buffer = buffer)

            controller.onMotion(motion)

            assertEquals(SessionState.Paused(PauseReason.Motion), controller.state.value)
            assertFalse(controller.acceptFrames.value)
            assertEquals(1, buffer.wipeCalls)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        buffer: FakeCaptureBuffer,
    ) = CaptureSessionController(
        sampler = FakeCaptureSampler(),
        buffer = buffer,
        preview = FakeCapturePreview(),
        gimbal = FakeCaptureGimbal(),
        clock = MonotonicClock { testScheduler.currentTime * 1_000 },
        scope = backgroundScope,
    )
}

internal class FakeCaptureSampler(
    private val events: MutableList<String>? = null,
) : CaptureSamplerPort {
    override var isRunning: Boolean = true
    var stopCalls = 0

    override suspend fun stop() {
        events?.add("sampler.stop")
        stopCalls += 1
        isRunning = false
    }
}

internal class FakeCaptureBuffer(
    private val events: MutableList<String>? = null,
) : CaptureBufferPort {
    var completeCoverage = false
    var frames = 8
    var wipeCalls = 0

    override suspend fun wipe() {
        events?.add("buffer.wipe")
        wipeCalls += 1
        frames = 0
    }

    override suspend fun frameCount(): Int = frames

    override suspend fun hasCompleteCoverage(endingAtUs: Long, lookbackUs: Long): Boolean =
        completeCoverage
}

internal class FakeCapturePreview(
    private val events: MutableList<String>? = null,
) : CapturePreviewPort {
    var visible = true

    override fun hide() {
        events?.add("preview.hide")
        visible = false
    }
}

internal class FakeCaptureGimbal(
    private val events: MutableList<String>? = null,
) : CaptureGimbalPort {
    var holdCalls = 0

    override fun requestHold() {
        events?.add("gimbal.hold")
        holdCalls += 1
    }
}
