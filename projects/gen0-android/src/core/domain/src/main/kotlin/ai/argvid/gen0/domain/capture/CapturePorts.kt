package ai.argvid.gen0.domain.capture

interface CaptureSamplerPort {
    val isRunning: Boolean
    suspend fun stop()
}

interface CaptureBufferPort {
    suspend fun wipe()
    suspend fun frameCount(): Int
    suspend fun hasCompleteCoverage(endingAtUs: Long, lookbackUs: Long): Boolean
}

fun interface CapturePreviewPort {
    fun hide()
}

fun interface CaptureGimbalPort {
    fun requestHold()
}
