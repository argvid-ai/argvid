package ai.argvid.gen0.media.codec

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.argvid.gen0.domain.moment.OwnedRescueAsset
import ai.argvid.gen0.domain.moment.QualityTier
import ai.argvid.gen0.domain.moment.RescueFrame
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
