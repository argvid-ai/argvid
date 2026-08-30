package ai.argvid.gen0.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameProcessorTest {
    @Test
    fun maskedPixelsAreBlackBeforeJpegEncoderReceivesThem() {
        val encoder = RecordingJpegEncoder()
        val processor = DefaultFrameProcessor(
            configuration = ProxyConfiguration(width = 4, height = 4, targetFps = 8),
            jpegEncoder = encoder,
        )

        val result = processor.process(
            source = solidWhiteNv21(width = 4, height = 4),
            geometry = CameraGeometry(rotationDegrees = 0),
            masks = listOf(FrameMask(left = 0f, top = 0f, right = 0.5f, bottom = 0.5f)),
            timestampUs = 1,
        )

        assertTrue(result is FrameProcessResult.Accepted)
        val pixels = encoder.lastPixels ?: error("encoder did not receive pixels")
        assertTrue((0 until 2).flatMap { y -> (0 until 2).map { x -> pixels[y * 4 + x] } }.all { it == BLACK })
        assertTrue(pixels[3 * 4 + 3] != BLACK)
    }

    @Test
    fun unsupportedGeometryFailsClosedBeforeEncoding() {
        val encoder = RecordingJpegEncoder()
        val processor = DefaultFrameProcessor(
            configuration = ProxyConfiguration(width = 4, height = 4, targetFps = 8),
            jpegEncoder = encoder,
        )

        val result = processor.process(
            source = solidWhiteNv21(4, 4),
            geometry = CameraGeometry(rotationDegrees = 45),
            masks = listOf(FrameMask(0f, 0f, 0.5f, 0.5f)),
            timestampUs = 1,
        )

        assertEquals(FrameProcessResult.Rejected(FrameRejected.MaskMappingFailed), result)
        assertEquals(null, encoder.lastPixels)
    }

    @Test
    fun normalizedMasksRejectNanOutOfRangeAndInvertedRectangles() {
        assertInvalidMask { FrameMask(Float.NaN, 0f, 1f, 1f) }
        assertInvalidMask { FrameMask(-0.1f, 0f, 1f, 1f) }
        assertInvalidMask { FrameMask(0.8f, 0f, 0.2f, 1f) }
        assertInvalidMask { FrameMask(0f, 1f, 1f, 1f) }
    }

    @Test
    fun sourceCropIsMappedIntoRotatedOutputCoordinates() {
        val sourceLeftHalf = NormalizedCrop(0f, 0f, 0.5f, 1f)

        assertEquals(NormalizedCrop(0f, 0f, 1f, 0.5f), mapCropAfterRotation(sourceLeftHalf, 90))
        assertEquals(NormalizedCrop(0.5f, 0f, 1f, 1f), mapCropAfterRotation(sourceLeftHalf, 180))
        assertEquals(NormalizedCrop(0f, 0.5f, 1f, 1f), mapCropAfterRotation(sourceLeftHalf, 270))
    }

    private fun assertInvalidMask(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private fun solidWhiteNv21(width: Int, height: Int): YuvFrame {
        val ySize = width * height
        return YuvFrame.Nv21(
            width = width,
            height = height,
            bytes = ByteArray(ySize + ySize / 2).also { bytes ->
                bytes.fill(235.toByte(), fromIndex = 0, toIndex = ySize)
                bytes.fill(128.toByte(), fromIndex = ySize)
            },
        )
    }

    private class RecordingJpegEncoder : JpegEncoder {
        var lastPixels: IntArray? = null

        override fun encodeArgb(width: Int, height: Int, pixels: IntArray, quality: Int): ByteArray {
            lastPixels = pixels.copyOf()
            return byteArrayOf(1, 2, 3)
        }
    }

    companion object {
        private const val BLACK = -0x1000000
    }
}
