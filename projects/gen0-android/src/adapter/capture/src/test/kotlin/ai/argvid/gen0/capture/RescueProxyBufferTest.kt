package ai.argvid.gen0.capture

import ai.argvid.gen0.domain.moment.QualityTier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueProxyBufferTest {
    @Test
    fun ringKeepsOnlyLatestFifteenSeconds() = runTest {
        val buffer = RescueProxyBuffer(ProxyConfiguration.p540())
        framesInclusive(0, 20_000_000, 125_000).forEach { buffer.append(it) }

        val snapshot = buffer.ownedSnapshot(endingAtUs = 20_000_000, lookbackUs = 15_000_000)

        assertTrue(snapshot.frames.first().timestampUs in 5_000_000..5_125_000)
        assertEquals(20_000_000, snapshot.frames.last().timestampUs)
        assertEquals(20_000_000, snapshot.requestEndUs)
        assertEquals(QualityTier.Proxy, snapshot.qualityTier)
        assertTrue(snapshot.coverage.isComplete)
    }

    @Test
    fun wipingSharedRingDoesNotDestroyOwnedSnapshot() = runTest {
        val sourceBytes = byteArrayOf(1, 2, 3)
        val buffer = RescueProxyBuffer(ProxyConfiguration.p540())
        framesInclusive(0, 15_000_000, 125_000, sourceBytes).forEach { buffer.append(it) }
        sourceBytes[0] = 99
        val owned = buffer.ownedSnapshot(15_000_000, 15_000_000)

        buffer.wipe()

        assertTrue(owned.frames.all { it.jpeg.isNotEmpty() })
        assertEquals(1, owned.frames.first().jpeg.first().toInt())
        assertEquals(0, buffer.frameCount())
        assertEquals(0, buffer.logicalByteCount())
    }

    @Test
    fun momentSnapshotOwnsBytesAndPreservesCoverage() = runTest {
        val sourceBytes = byteArrayOf(4, 5, 6)
        val buffer = RescueProxyBuffer(ProxyConfiguration.p540())
        framesInclusive(0, 15_000_000, 125_000, sourceBytes).forEach { buffer.append(it) }

        val owned = buffer.ownedMomentSnapshot(15_000_000, 15_000_000)
        buffer.wipe()
        sourceBytes[0] = 99

        assertTrue(owned.coverageComplete)
        assertEquals(QualityTier.Proxy, owned.qualityTier)
        assertEquals(4, owned.frames.first().jpeg.first().toInt())
    }

    @Test
    fun coverageRejectsMissingEndpointsLargeGapsAndOneFrame() = runTest {
        val configuration = ProxyConfiguration.p540()

        val missingStart = RescueProxyBuffer(configuration)
        framesInclusive(1_000_000, 15_000_000, 125_000).forEach { missingStart.append(it) }
        assertFalse(missingStart.coverage(15_000_000, 15_000_000).isComplete)

        val missingEnd = RescueProxyBuffer(configuration)
        framesInclusive(0, 14_000_000, 125_000).forEach { missingEnd.append(it) }
        assertFalse(missingEnd.coverage(15_000_000, 15_000_000).isComplete)

        val largeGap = RescueProxyBuffer(configuration)
        framesInclusive(0, 7_000_000, 125_000).forEach { largeGap.append(it) }
        framesInclusive(8_000_000, 15_000_000, 125_000).forEach { largeGap.append(it) }
        assertFalse(largeGap.coverage(15_000_000, 15_000_000).isComplete)
        assertTrue(largeGap.coverage(15_000_000, 15_000_000).largestGapUs > 250_000)

        val oneFrame = RescueProxyBuffer(configuration)
        oneFrame.append(frame(15_000_000))
        assertFalse(oneFrame.coverage(15_000_000, 15_000_000).isComplete)
    }

    @Test
    fun nonIncreasingTimestampAndMismatchedDimensionsAreRejected() = runTest {
        val buffer = RescueProxyBuffer(ProxyConfiguration.p540())
        buffer.append(frame(1_000))

        assertIllegalArgument { buffer.append(frame(1_000)) }
        assertIllegalArgument { buffer.append(frame(2_000, width = 640, height = 360)) }
    }

    @Test
    fun logicalByteBudgetEvictsOldestFrames() = runTest {
        val configuration = ProxyConfiguration(
            width = 960,
            height = 540,
            targetFps = 8,
            retentionUs = 15_000_000,
            maxLogicalBytes = 6,
        )
        val buffer = RescueProxyBuffer(configuration)

        buffer.append(frame(0, jpeg = byteArrayOf(1, 1, 1)))
        buffer.append(frame(125_000, jpeg = byteArrayOf(2, 2, 2)))
        buffer.append(frame(250_000, jpeg = byteArrayOf(3, 3, 3)))

        assertEquals(2, buffer.frameCount())
        assertEquals(6, buffer.logicalByteCount())
        assertEquals(125_000, buffer.ownedSnapshot(250_000, 250_000).frames.first().timestampUs)
    }

    private suspend fun assertIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private fun framesInclusive(
        startUs: Long,
        endUs: Long,
        intervalUs: Long,
        jpeg: ByteArray = byteArrayOf(1),
    ): List<ProxyFrame> = generateSequence(startUs) { it + intervalUs }
        .takeWhile { it <= endUs }
        .map { frame(it, jpeg = jpeg) }
        .toList()

    private fun frame(
        timestampUs: Long,
        width: Int = 960,
        height: Int = 540,
        jpeg: ByteArray = byteArrayOf(1),
    ) = ProxyFrame(timestampUs, width, height, jpeg)
}
