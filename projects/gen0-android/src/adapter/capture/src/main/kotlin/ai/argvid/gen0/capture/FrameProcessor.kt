package ai.argvid.gen0.capture

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.floor

sealed interface YuvFrame {
    val width: Int
    val height: Int

    data class Nv21(
        override val width: Int,
        override val height: Int,
        val bytes: ByteArray,
    ) : YuvFrame
}

enum class FrameRejected {
    InvalidSource,
    MaskMappingFailed,
    EncodingFailed,
}

sealed interface FrameProcessResult {
    data class Accepted(val frame: ProxyFrame) : FrameProcessResult
    data class Rejected(val reason: FrameRejected) : FrameProcessResult
}

fun interface JpegEncoder {
    fun encodeArgb(width: Int, height: Int, pixels: IntArray, quality: Int): ByteArray
}

fun interface FrameMetricsSink {
    fun record(durationUs: Long, encodedBytes: Int)
}

fun interface FrameProcessor {
    fun process(
        source: YuvFrame,
        geometry: CameraGeometry,
        masks: List<FrameMask>,
        timestampUs: Long,
    ): FrameProcessResult
}

class DefaultFrameProcessor(
    private val configuration: ProxyConfiguration,
    private val jpegEncoder: JpegEncoder = AndroidJpegEncoder(),
    private val metricsSink: FrameMetricsSink = FrameMetricsSink { _, _ -> },
    private val nanoTime: () -> Long = System::nanoTime,
) : FrameProcessor {
    override fun process(
        source: YuvFrame,
        geometry: CameraGeometry,
        masks: List<FrameMask>,
        timestampUs: Long,
    ): FrameProcessResult {
        if (geometry.rotationDegrees !in SUPPORTED_ROTATIONS) {
            return FrameProcessResult.Rejected(FrameRejected.MaskMappingFailed)
        }
        if (timestampUs < 0 || source.width <= 0 || source.height <= 0) {
            return FrameProcessResult.Rejected(FrameRejected.InvalidSource)
        }

        val startedNs = nanoTime()
        return try {
            val decoded = when (source) {
                is YuvFrame.Nv21 -> decodeNv21(source)
                    ?: return FrameProcessResult.Rejected(FrameRejected.InvalidSource)
            }
            val rotated = decoded.rotate(geometry.rotationDegrees)
            val cropped = rotated.crop(geometry.crop)
            cropped.applyMasks(masks)
            val resized = cropped.resize(configuration.width, configuration.height)
            val jpeg = jpegEncoder.encodeArgb(resized.width, resized.height, resized.pixels, JPEG_QUALITY)
            if (jpeg.isEmpty()) return FrameProcessResult.Rejected(FrameRejected.EncodingFailed)
            metricsSink.record((nanoTime() - startedNs) / 1_000, jpeg.size)
            FrameProcessResult.Accepted(
                ProxyFrame(timestampUs, configuration.width, configuration.height, jpeg),
            )
        } catch (_: RuntimeException) {
            FrameProcessResult.Rejected(FrameRejected.EncodingFailed)
        }
    }

    private fun decodeNv21(source: YuvFrame.Nv21): PixelImage? {
        if (source.width % 2 != 0 || source.height % 2 != 0) return null
        val ySize = source.width * source.height
        if (source.bytes.size != ySize + ySize / 2) return null
        val pixels = IntArray(ySize)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val yValue = (source.bytes[y * source.width + x].toInt() and 0xff) - 16
                val chroma = ySize + (y / 2) * source.width + (x and -2)
                val v = (source.bytes[chroma].toInt() and 0xff) - 128
                val u = (source.bytes[chroma + 1].toInt() and 0xff) - 128
                val scaledY = 1192 * yValue.coerceAtLeast(0)
                val red = (scaledY + 1634 * v).coerceIn(0, 262143) shr 10
                val green = (scaledY - 833 * v - 400 * u).coerceIn(0, 262143) shr 10
                val blue = (scaledY + 2066 * u).coerceIn(0, 262143) shr 10
                pixels[y * source.width + x] = BLACK or (red shl 16) or (green shl 8) or blue
            }
        }
        return PixelImage(source.width, source.height, pixels)
    }

    private data class PixelImage(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    ) {
        fun rotate(degrees: Int): PixelImage = when (degrees) {
            0 -> copy(pixels = pixels.copyOf())
            90 -> PixelImage(height, width, IntArray(pixels.size)).also { output ->
                for (y in 0 until height) for (x in 0 until width) {
                    output.pixels[x * output.width + (height - 1 - y)] = pixels[y * width + x]
                }
            }
            180 -> PixelImage(width, height, IntArray(pixels.size)).also { output ->
                for (index in pixels.indices) output.pixels[pixels.lastIndex - index] = pixels[index]
            }
            270 -> PixelImage(height, width, IntArray(pixels.size)).also { output ->
                for (y in 0 until height) for (x in 0 until width) {
                    output.pixels[(width - 1 - x) * output.width + y] = pixels[y * width + x]
                }
            }
            else -> error("rotation was validated")
        }

        fun crop(crop: NormalizedCrop): PixelImage {
            val left = floor(crop.left * width).toInt().coerceIn(0, width - 1)
            val top = floor(crop.top * height).toInt().coerceIn(0, height - 1)
            val right = ceil(crop.right * width).toInt().coerceIn(left + 1, width)
            val bottom = ceil(crop.bottom * height).toInt().coerceIn(top + 1, height)
            val outputWidth = right - left
            val outputHeight = bottom - top
            return PixelImage(outputWidth, outputHeight, IntArray(outputWidth * outputHeight)).also { output ->
                for (y in 0 until outputHeight) {
                    pixels.copyInto(
                        destination = output.pixels,
                        destinationOffset = y * outputWidth,
                        startIndex = (top + y) * width + left,
                        endIndex = (top + y) * width + right,
                    )
                }
            }
        }

        fun applyMasks(masks: List<FrameMask>) {
            masks.forEach { mask ->
                val left = floor(mask.left * width).toInt().coerceIn(0, width - 1)
                val top = floor(mask.top * height).toInt().coerceIn(0, height - 1)
                val right = ceil(mask.right * width).toInt().coerceIn(left + 1, width)
                val bottom = ceil(mask.bottom * height).toInt().coerceIn(top + 1, height)
                for (y in top until bottom) {
                    pixels.fill(BLACK, y * width + left, y * width + right)
                }
            }
        }

        fun resize(targetWidth: Int, targetHeight: Int): PixelImage {
            if (width == targetWidth && height == targetHeight) return this
            val output = IntArray(targetWidth * targetHeight)
            for (y in 0 until targetHeight) {
                val sourceY = y * height / targetHeight
                for (x in 0 until targetWidth) {
                    val sourceX = x * width / targetWidth
                    output[y * targetWidth + x] = pixels[sourceY * width + sourceX]
                }
            }
            return PixelImage(targetWidth, targetHeight, output)
        }
    }

    companion object {
        private val SUPPORTED_ROTATIONS = setOf(0, 90, 180, 270)
        private const val JPEG_QUALITY = 70
        private const val BLACK = -0x1000000
    }
}

class AndroidJpegEncoder : JpegEncoder {
    override fun encodeArgb(width: Int, height: Int, pixels: IntArray, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
