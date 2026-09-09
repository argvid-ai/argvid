package ai.argvid.gen0.media.codec

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.argvid.gen0.domain.moment.OwnedRescueAsset
import ai.argvid.gen0.domain.moment.QualityTier
import ai.argvid.gen0.domain.moment.RescueFrame
import ai.argvid.gen0.domain.moment.OwnedPcmAudio
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidProxyMovieEncoderTest {
    @Test
    fun encodesVideoAndAacOnTheSameTimeline() = runTest {
        val pcm = ByteArray(48_000 * 2 * 2)
        repeat(pcm.size / 2) { index ->
            val value = (kotlin.math.sin(index * 2.0 * Math.PI * 440 / 48_000) * 8_000).toInt()
            pcm[index * 2] = value.toByte()
            pcm[index * 2 + 1] = (value shr 8).toByte()
        }
        val encoder = AndroidProxyMovieEncoder(freshStagingDirectory(), requireAudio = true)
        val encoded = encoder.encode(syntheticRescueAsset().copy(audio = OwnedPcmAudio(48_000, pcm)))
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(encoded.stagingPath)
            assertEquals(2, extractor.trackCount)
            val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }.toSet()
            assertEquals(setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_AAC), types)
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                assertTrue(abs(format.getLong(MediaFormat.KEY_DURATION) - 2_000_000) < 250_000)
                extractor.selectTrack(track)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                assertTrue(extractor.sampleTime in 0..125_000)
                assertTrue(extractor.readSampleData(java.nio.ByteBuffer.allocate(1_000_000), 0) > 0)
                extractor.unselectTrack(track)
            }
            assertAudibleDecodedAudio(encoded.stagingPath)
        } finally { extractor.release(); encoder.discard(encoded) }
    }

    private fun assertAudibleDecodedAudio(path: String) {
        val extractor = MediaExtractor()
        var decoder: android.media.MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
            }
            extractor.selectTrack(track)
            val codec = android.media.MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            decoder = codec
            codec.configure(extractor.getTrackFormat(track), null, null, 0)
            codec.start()
            started = true
            var inputEnded = false
            var audibleSamples = 0
            val info = android.media.MediaCodec.BufferInfo()
            val deadlineNs = System.nanoTime() + 10_000_000_000
            while (true) {
                check(System.nanoTime() < deadlineNs) { "AAC decoder timed out" }
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = checkNotNull(codec.getInputBuffer(inputIndex))
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    try {
                        val output = checkNotNull(codec.getOutputBuffer(outputIndex)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        while (output.remaining() >= 2) if (abs(output.short.toInt()) > 500) audibleSamples++
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } finally { codec.releaseOutputBuffer(outputIndex, false) }
                }
            }
            assertTrue("Synthetic tone must decode to non-silent PCM", audibleSamples > 48_000)
        } finally {
            if (started) runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }

    @Test
    fun encodesReadableMovieWithExpectedDurationAndRotation() = runTest {
        val directory = freshStagingDirectory()
        val encoder = AndroidProxyMovieEncoder(directory)
        val encoded = encoder.encode(syntheticRescueAsset(rotationDegrees = 90))

        val extractor = MediaExtractor()
        extractor.setDataSource(encoded.stagingPath)
        val videoTrack = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        val format = extractor.getTrackFormat(videoTrack)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
        assertTrue(abs(encoded.durationUs - 2_000_000) <= 200_000)
        extractor.release()

        val metadata = MediaMetadataRetriever()
        metadata.setDataSource(encoded.stagingPath)
        assertEquals("90", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
        metadata.release()

        encoder.discard(encoded)
        assertFalse(File(encoded.stagingPath).exists())
    }

    @Test
    fun codecFailureRemovesPartialOutput() = runTest {
        val directory = freshStagingDirectory()
        val encoder = AndroidProxyMovieEncoder(directory)
        val invalid = syntheticRescueAsset().copy(
            frames = listOf(RescueFrame(0, 960, 540, byteArrayOf(1, 2, 3))),
        )

        try {
            encoder.encode(invalid)
            throw AssertionError("Expected encode failure")
        } catch (_: IllegalArgumentException) {
            Unit
        }

        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun syntheticRescueAsset(rotationDegrees: Int = 0): OwnedRescueAsset {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val names = context.assets.list("proxy-frames").orEmpty()
            .filter { it.endsWith(".jpg") }
            .sorted()
        require(names.size == 16)
        return OwnedRescueAsset(
            frames = names.mapIndexed { index, name ->
                RescueFrame(
                    timestampUs = index * 125_000L,
                    width = 960,
                    height = 540,
                    jpeg = context.assets.open("proxy-frames/$name").use { it.readBytes() },
                )
            },
            requestStartUs = 0,
            requestEndUs = 2_000_000,
            coverageComplete = true,
            qualityTier = QualityTier.Proxy,
            rotationDegrees = rotationDegrees,
        )
    }

    private fun freshStagingDirectory(): File {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return File(context.cacheDir, "proxy-encoder-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }
}
