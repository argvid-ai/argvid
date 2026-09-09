package ai.argvid.gen0.domain.capture

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AudioVideoSamplerTest {
    @Test fun stopsBothSourcesEvenWhenMicrophoneStopFails() = runTest {
        var videoStopped = false
        val video = object : CaptureSamplerPort {
            override val isRunning get() = !videoStopped
            override suspend fun stop() { videoStopped = true }
        }
        val audio = object : CaptureSamplerPort {
            override val isRunning = true
            override suspend fun stop() { throw IllegalStateException("synthetic microphone failure") }
        }
        val sampler = AudioVideoSampler(video, audio)
        assertTrue(sampler.isRunning)
        try { sampler.stop(); fail("Expected failure") } catch (_: IllegalStateException) { }
        assertTrue(videoStopped)
        assertFalse(sampler.isRunning)
    }
}
