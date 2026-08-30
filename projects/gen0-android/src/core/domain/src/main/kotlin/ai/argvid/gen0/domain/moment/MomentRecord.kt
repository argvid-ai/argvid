package ai.argvid.gen0.domain.moment

data class MomentRecord(
    val reference: SavedMomentReference,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val qualityTier: QualityTier,
    val stagingPath: String? = null,
)
