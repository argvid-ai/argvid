package ai.argvid.gen0.media.codec

import ai.argvid.gen0.domain.moment.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioRequirementTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun requiredAudioCannotSilentlyProduceVideoOnlyOrAnUnsynchronizedFile() = runTest {
        val directory = temporary.newFolder()
        val encoder = AndroidProxyMovieEncoder(directory, requireAudio = true)
        val video = OwnedRescueAsset(
            listOf(RescueFrame(0, 2, 2, byteArrayOf(1))), 0, 15_000_000, true, QualityTier.Proxy,
        )
        for (asset in listOf(video, video.copy(audio = OwnedPcmAudio(48_000, ByteArray(96_000))))) {
            try { encoder.encode(asset); fail("Expected rejection before codec/file creation") }
            catch (_: IllegalArgumentException) { }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }
}
