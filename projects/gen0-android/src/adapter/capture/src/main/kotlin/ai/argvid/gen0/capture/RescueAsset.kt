package ai.argvid.gen0.capture

import ai.argvid.gen0.domain.moment.QualityTier

data class ProxyCoverage(
    val requestStartUs: Long,
    val requestEndUs: Long,
    val actualStartUs: Long?,
    val actualEndUs: Long?,
    val largestGapUs: Long,
    val isComplete: Boolean,
)

data class RescueAsset(
    val frames: List<ProxyFrame>,
    val requestStartUs: Long,
    val requestEndUs: Long,
    val coverage: ProxyCoverage,
    val qualityTier: QualityTier = QualityTier.Proxy,
)

data class ProxyBufferDiagnostics(
    val frameCount: Int,
    val logicalByteCount: Long,
    val earliestTimestampUs: Long?,
    val latestTimestampUs: Long?,
)
