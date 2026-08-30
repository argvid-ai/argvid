package ai.argvid.gen0.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFrameAnalyzerTest {
    @Test
    fun throttledAndProcessedFramesAreEachClosedExactlyOnce() {
        val processor = RecordingFrameProcessor()
        val analyzer = CameraFrameAnalyzer(
            targetIntervalUs = 125_000,
            processor = processor,
            masks = emptyList(),
            onAccepted = {},
            onRejected = {},
        )
        val first = FakeCameraFrame(timestampUs = 1_000_000)
        val throttled = FakeCameraFrame(timestampUs = 1_050_000)

        analyzer.analyze(first)
        analyzer.analyze(throttled)

        assertEquals(1, processor.calls)
        assertEquals(1, first.closeCount)
        assertEquals(1, throttled.closeCount)
        assertEquals(1, first.readCount)
        assertEquals(0, throttled.readCount)
    }

    @Test
    fun processorFailureStillClosesFrameExactlyOnce() {
        val analyzer = CameraFrameAnalyzer(
            targetIntervalUs = 125_000,
            processor = FrameProcessor { _, _, _, _ -> error("synthetic failure") },
            masks = emptyList(),
            onAccepted = {},
            onRejected = {},
        )
        val frame = FakeCameraFrame(timestampUs = 1_000_000)

        analyzer.analyze(frame)

        assertEquals(1, frame.closeCount)
    }

    private class RecordingFrameProcessor : FrameProcessor {
        var calls = 0

        override fun process(
            source: YuvFrame,
            geometry: CameraGeometry,
            masks: List<FrameMask>,
            timestampUs: Long,
        ): FrameProcessResult {
            calls++
            return FrameProcessResult.Accepted(ProxyFrame(timestampUs, 4, 4, byteArrayOf(1)))
        }
    }

    private class FakeCameraFrame(
        override val timestampUs: Long,
    ) : CloseableCameraFrame {
        override val geometry = CameraGeometry(0)
        var closeCount = 0
        var readCount = 0

        override fun readYuv(): YuvFrame {
            readCount++
            return YuvFrame.Nv21(4, 4, ByteArray(24))
        }

        override fun close() {
            closeCount++
        }
    }
}
