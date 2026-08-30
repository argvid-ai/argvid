package ai.argvid.gen0.domain.capture

import kotlin.math.ceil

data class CaptureDiagnosticsSnapshot(
    val sampleCount: Int,
    val stopP95Us: Long?,
    val stopMaxUs: Long?,
)

class CaptureDiagnostics {
    private val lock = Any()
    private val stopDurationsUs = mutableListOf<Long>()

    fun recordStop(durationUs: Long) {
        require(durationUs >= 0)
        synchronized(lock) {
            stopDurationsUs += durationUs
        }
    }

    fun snapshot(): CaptureDiagnosticsSnapshot = synchronized(lock) {
        val ordered = stopDurationsUs.sorted()
        CaptureDiagnosticsSnapshot(
            sampleCount = ordered.size,
            stopP95Us = ordered.nearestRank(0.95),
            stopMaxUs = ordered.lastOrNull(),
        )
    }

    private fun List<Long>.nearestRank(percentile: Double): Long? {
        if (isEmpty()) return null
        val rank = ceil(percentile * size).toInt().coerceIn(1, size)
        return this[rank - 1]
    }
}
