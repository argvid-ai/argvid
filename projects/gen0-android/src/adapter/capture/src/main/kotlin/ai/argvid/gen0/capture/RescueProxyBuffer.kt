package ai.argvid.gen0.capture

import ai.argvid.gen0.domain.capture.CaptureBufferPort
import ai.argvid.gen0.domain.moment.MomentRescueSource
import ai.argvid.gen0.domain.moment.OwnedRescueAsset
import ai.argvid.gen0.domain.moment.RescueFrame
import java.util.ArrayDeque
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RescueProxyBuffer(
    private val configuration: ProxyConfiguration,
) : CaptureBufferPort, MomentRescueSource {
    private val mutex = Mutex()
    private val frames = ArrayDeque<ProxyFrame>()
    private var logicalBytes = 0L
    private var latestTimestampUs: Long? = null

    suspend fun append(frame: ProxyFrame) = mutex.withLock {
        require(frame.timestampUs >= 0)
        require(frame.width == configuration.width && frame.height == configuration.height)
        require(frame.jpeg.isNotEmpty())
        require(frame.jpeg.size.toLong() <= configuration.maxLogicalBytes)
        latestTimestampUs?.let { require(frame.timestampUs > it) }

        val ownedFrame = frame.copy(jpeg = frame.jpeg.copyOf())
        frames.addLast(ownedFrame)
        logicalBytes += ownedFrame.jpeg.size
        latestTimestampUs = ownedFrame.timestampUs

        val oldestAllowedUs = ownedFrame.timestampUs - configuration.retentionUs
        while (frames.firstOrNull()?.timestampUs?.let { it < oldestAllowedUs } == true) {
            removeFirst()
        }
        while (logicalBytes > configuration.maxLogicalBytes) {
            removeFirst()
        }
    }

    suspend fun ownedSnapshot(
        endingAtUs: Long,
        lookbackUs: Long,
    ): RescueAsset = mutex.withLock {
        require(endingAtUs >= 0)
        require(lookbackUs > 0)
        val requestStartUs = endingAtUs - lookbackUs
        val ownedFrames = frames
            .asSequence()
            .filter { it.timestampUs in requestStartUs..endingAtUs }
            .map { it.copy(jpeg = it.jpeg.copyOf()) }
            .toList()
        RescueAsset(
            frames = ownedFrames,
            requestStartUs = requestStartUs,
            requestEndUs = endingAtUs,
            coverage = calculateCoverage(ownedFrames, requestStartUs, endingAtUs),
        )
    }

    suspend fun coverage(
        endingAtUs: Long,
        lookbackUs: Long,
    ): ProxyCoverage = mutex.withLock {
        require(endingAtUs >= 0)
        require(lookbackUs > 0)
        val requestStartUs = endingAtUs - lookbackUs
        val selectedFrames = frames.filter { it.timestampUs in requestStartUs..endingAtUs }
        calculateCoverage(selectedFrames, requestStartUs, endingAtUs)
    }

    override suspend fun wipe() = mutex.withLock {
        frames.clear()
        logicalBytes = 0
        latestTimestampUs = null
    }

    override suspend fun frameCount(): Int = mutex.withLock { frames.size }

    override suspend fun hasCompleteCoverage(
        endingAtUs: Long,
        lookbackUs: Long,
    ): Boolean = coverage(endingAtUs, lookbackUs).isComplete

    override suspend fun ownedMomentSnapshot(
        endingAtUs: Long,
        lookbackUs: Long,
    ): OwnedRescueAsset {
        val asset = ownedSnapshot(endingAtUs, lookbackUs)
        return OwnedRescueAsset(
            frames = asset.frames.map { frame ->
                RescueFrame(
                    timestampUs = frame.timestampUs,
                    width = frame.width,
                    height = frame.height,
                    jpeg = frame.jpeg.copyOf(),
                )
            },
            requestStartUs = asset.requestStartUs,
            requestEndUs = asset.requestEndUs,
            coverageComplete = asset.coverage.isComplete,
            qualityTier = asset.qualityTier,
        )
    }

    suspend fun logicalByteCount(): Long = mutex.withLock { logicalBytes }

    suspend fun diagnostics(): ProxyBufferDiagnostics = mutex.withLock {
        ProxyBufferDiagnostics(
            frameCount = frames.size,
            logicalByteCount = logicalBytes,
            earliestTimestampUs = frames.firstOrNull()?.timestampUs,
            latestTimestampUs = frames.lastOrNull()?.timestampUs,
        )
    }

    private fun calculateCoverage(
        selectedFrames: List<ProxyFrame>,
        requestStartUs: Long,
        requestEndUs: Long,
    ): ProxyCoverage {
        val largestGapUs = selectedFrames.zipWithNext { left, right -> right.timestampUs - left.timestampUs }
            .maxOrNull() ?: 0
        val intervalUs = configuration.targetIntervalUs
        val firstTimestampUs = selectedFrames.firstOrNull()?.timestampUs
        val lastTimestampUs = selectedFrames.lastOrNull()?.timestampUs
        val complete = selectedFrames.size > 1 &&
            firstTimestampUs != null && firstTimestampUs <= requestStartUs + intervalUs &&
            lastTimestampUs != null && lastTimestampUs >= requestEndUs - intervalUs &&
            largestGapUs <= intervalUs * 2
        return ProxyCoverage(
            requestStartUs = requestStartUs,
            requestEndUs = requestEndUs,
            actualStartUs = firstTimestampUs,
            actualEndUs = lastTimestampUs,
            largestGapUs = largestGapUs,
            isComplete = complete,
        )
    }

    private fun removeFirst() {
        logicalBytes -= frames.removeFirst().jpeg.size
    }
}
