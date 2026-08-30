package ai.argvid.gen0.domain.moment

data class RescueFrame(
    val timestampUs: Long,
    val width: Int,
    val height: Int,
    val jpeg: ByteArray,
)

data class OwnedRescueAsset(
    val frames: List<RescueFrame>,
    val requestStartUs: Long,
    val requestEndUs: Long,
    val coverageComplete: Boolean,
    val qualityTier: QualityTier,
    val rotationDegrees: Int = 0,
)

data class EncodedMoment(
    val stagingPath: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val qualityTier: QualityTier,
)

data class SavedMomentReference(
    val uri: String,
)

interface MomentRescueSource {
    suspend fun ownedMomentSnapshot(endingAtUs: Long, lookbackUs: Long): OwnedRescueAsset
}

interface MomentEncoder {
    suspend fun encode(asset: OwnedRescueAsset): EncodedMoment
    suspend fun discard(moment: EncodedMoment)
}

fun interface MomentSaver {
    suspend fun save(moment: EncodedMoment): SavedMomentReference
}

fun interface MomentCatalog {
    suspend fun insert(record: MomentRecord)
    suspend fun markStagingCleaned(reference: SavedMomentReference) {}
}
