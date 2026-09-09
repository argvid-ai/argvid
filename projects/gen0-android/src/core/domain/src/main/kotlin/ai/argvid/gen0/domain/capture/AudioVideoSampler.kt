package ai.argvid.gen0.domain.capture

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class AudioVideoSampler(
    private val video: CaptureSamplerPort,
    private val audio: CaptureSamplerPort,
) : CaptureSamplerPort {
    override val isRunning get() = video.isRunning && audio.isRunning
    override suspend fun stop() = withContext(NonCancellable) {
        try { audio.stop() } finally { video.stop() }
    }
}
