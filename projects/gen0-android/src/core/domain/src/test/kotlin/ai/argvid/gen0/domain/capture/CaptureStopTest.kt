package ai.argvid.gen0.domain.capture

import ai.argvid.gen0.domain.session.PauseReason
import ai.argvid.gen0.domain.session.SessionState
import ai.argvid.gen0.domain.time.MonotonicClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class CaptureStopTest {
    @Test
    fun stopUsesExactSafetyOrderAndPauses() = runTest {
        val events = mutableListOf<String>()
        val sampler = FakeCaptureSampler(events)
        val buffer = FakeCaptureBuffer(events)
        val preview = FakeCapturePreview(events)
        val gimbal = FakeCaptureGimbal(events)
        val clock = SequenceClock(10_000, 12_500)
        val diagnostics = CaptureDiagnostics()
        val controller = CaptureSessionController(
            sampler = sampler,
            buffer = buffer,
            preview = preview,
            gimbal = gimbal,
            clock = clock,
            scope = backgroundScope,
            diagnostics = diagnostics,
        )

        val result = controller.stop(StopReason.Screen)

        assertFalse(controller.acceptFrames.value)
        assertFalse(controller.rescueAvailable.value)
        assertFalse(sampler.isRunning)
        assertEquals(0, buffer.frameCount())
        assertFalse(preview.visible)
        assertEquals(1, gimbal.holdCalls)
        assertEquals(listOf("sampler.stop", "buffer.wipe", "preview.hide", "gimbal.hold"), events)
        assertEquals(SessionState.Paused(PauseReason.UserStop), result.state)
        assertEquals(2_500, result.durationUs)
        assertEquals(StopReason.Screen, result.reason)
        assertEquals(CaptureDiagnosticsSnapshot(1, 2_500, 2_500), diagnostics.snapshot())
    }

    @Test
    fun concurrentStopsShareOneResultAndOneMetric() = runTest {
        val sampler = FakeCaptureSampler()
        val buffer = FakeCaptureBuffer()
        val gimbal = FakeCaptureGimbal()
        val diagnostics = CaptureDiagnostics()
        val controller = CaptureSessionController(
            sampler = sampler,
            buffer = buffer,
            preview = FakeCapturePreview(),
            gimbal = gimbal,
            clock = SequenceClock(0, 1_000),
            scope = backgroundScope,
            diagnostics = diagnostics,
        )

        val results = List(20) { async { controller.stop(StopReason.Screen) } }.awaitAll()

        results.forEach { assertSame(results.first(), it) }
        assertEquals(1, sampler.stopCalls)
        assertEquals(1, buffer.wipeCalls)
        assertEquals(1, gimbal.holdCalls)
        assertEquals(1, diagnostics.snapshot().sampleCount)
    }

    @Test
    fun oneHundredStopsReportNearestRankP95() = runTest {
        val diagnostics = CaptureDiagnostics()

        for (durationUs in 1L..100L) {
            val controller = CaptureSessionController(
                sampler = FakeCaptureSampler(),
                buffer = FakeCaptureBuffer(),
                preview = FakeCapturePreview(),
                gimbal = FakeCaptureGimbal(),
                clock = SequenceClock(1_000, 1_000 + durationUs),
                scope = backgroundScope,
                diagnostics = diagnostics,
            )
            controller.stop(StopReason.Screen)
        }

        assertEquals(CaptureDiagnosticsSnapshot(100, 95, 100), diagnostics.snapshot())
    }
}

private class SequenceClock(vararg values: Long) : MonotonicClock {
    private val iterator = values.iterator()
    override fun nowUs(): Long = iterator.next()
}
