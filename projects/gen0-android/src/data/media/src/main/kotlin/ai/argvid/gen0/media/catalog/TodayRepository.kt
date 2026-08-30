package ai.argvid.gen0.media.catalog

import ai.argvid.gen0.media.db.MomentDao
import ai.argvid.gen0.media.db.MomentDbStatus
import ai.argvid.gen0.media.db.MomentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class TodayMoment(
    val id: String,
    val mediaUri: String,
    val durationUs: Long,
    val createdAt: String,
    val qualityTier: String,
    val cleanupPending: Boolean = false,
)

sealed interface TodayAssetResult {
    data class Playable(val moment: TodayMoment) : TodayAssetResult
    data class AssetMissing(val momentId: String) : TodayAssetResult
    data class Retryable(val momentId: String, val error: AssetError) : TodayAssetResult
}

interface TodayMomentStore {
    val latestSaved: Flow<MomentEntity?>
    suspend fun get(id: String): MomentEntity?
    suspend fun updateAssetState(id: String, status: MomentDbStatus, mediaUri: String?): Int
    suspend fun markViewed(id: String, viewedAt: String): Int
}

class RoomTodayMomentStore(private val dao: MomentDao) : TodayMomentStore {
    override val latestSaved: Flow<MomentEntity?> = dao.observeLatestPlayableCandidate()
    override suspend fun get(id: String): MomentEntity? = dao.get(id)
    override suspend fun updateAssetState(id: String, status: MomentDbStatus, mediaUri: String?) =
        dao.updateAssetState(id, status, mediaUri)
    override suspend fun markViewed(id: String, viewedAt: String) = dao.markViewed(id, viewedAt)
}

class TodayRepository(
    private val store: TodayMomentStore,
    private val verifier: AssetVerifier,
) {
    val latest: Flow<TodayMoment?> = store.latestSaved.map { entity ->
        entity
            ?.takeIf { it.status == MomentDbStatus.SAVED || it.status == MomentDbStatus.SAVED_WITH_CLEANUP_PENDING }
            ?.toTodayMomentOrNull()
    }

    suspend fun refresh(momentId: String): TodayAssetResult {
        val entity = store.get(momentId) ?: return TodayAssetResult.AssetMissing(momentId)
        if (entity.status != MomentDbStatus.SAVED && entity.status != MomentDbStatus.SAVED_WITH_CLEANUP_PENDING) {
            return TodayAssetResult.AssetMissing(momentId)
        }
        val uri = entity.mediaUri ?: return markMissing(momentId)
        return when (val check = verifier.check(uri)) {
            AssetCheck.Present -> TodayAssetResult.Playable(checkNotNull(entity.toTodayMomentOrNull()))
            AssetCheck.NotFound -> markMissing(momentId)
            is AssetCheck.Retryable -> TodayAssetResult.Retryable(momentId, check.error)
        }
    }

    suspend fun markViewed(momentId: String, viewedAt: String) {
        store.markViewed(momentId, viewedAt)
    }

    private suspend fun markMissing(momentId: String): TodayAssetResult.AssetMissing {
        store.updateAssetState(momentId, MomentDbStatus.ASSET_MISSING, null)
        return TodayAssetResult.AssetMissing(momentId)
    }
}

private fun MomentEntity.toTodayMomentOrNull(): TodayMoment? = mediaUri?.let { uri ->
    TodayMoment(id, uri, durationUs, createdAt, qualityTier, stagingPath != null)
}
