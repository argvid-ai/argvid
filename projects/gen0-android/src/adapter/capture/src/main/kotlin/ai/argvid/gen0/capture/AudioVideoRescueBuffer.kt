package ai.argvid.gen0.capture

import ai.argvid.gen0.domain.capture.CaptureBufferPort
import ai.argvid.gen0.domain.moment.MomentRescueSource
import ai.argvid.gen0.domain.moment.PcmAudioBuffer

class AudioVideoRescueBuffer(
    private val video: RescueProxyBuffer,
    private val audio: PcmAudioBuffer,
    private val audioRunning: () -> Boolean,
) : CaptureBufferPort, MomentRescueSource {
    override suspend fun wipe() { video.wipe(); audio.wipe() }
    override suspend fun frameCount() = video.frameCount()
    override suspend fun hasCompleteCoverage(endingAtUs: Long, lookbackUs: Long) =
        audioRunning() && video.hasCompleteCoverage(endingAtUs, lookbackUs) &&
            audio.hasCoverage(endingAtUs - lookbackUs, endingAtUs)

    override suspend fun ownedMomentSnapshot(endingAtUs: Long, lookbackUs: Long): ai.argvid.gen0.domain.moment.OwnedRescueAsset {
        val snapshot = video.ownedRecentMomentSnapshot(endingAtUs, lookbackUs)
        val samples = audio.snapshot(snapshot.requestStartUs, snapshot.requestEndUs)
        return snapshot.copy(audio = samples, coverageComplete = snapshot.coverageComplete && samples != null && audioRunning())
    }
}
