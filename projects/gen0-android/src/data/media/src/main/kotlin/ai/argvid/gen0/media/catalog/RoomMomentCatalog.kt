package ai.argvid.gen0.media.catalog

import ai.argvid.gen0.domain.moment.MomentCatalog
import ai.argvid.gen0.domain.moment.MomentRecord
import ai.argvid.gen0.domain.moment.SavedMomentReference
import ai.argvid.gen0.media.db.Gen0Database
import ai.argvid.gen0.media.db.MomentDbStatus
import ai.argvid.gen0.media.db.MomentEntity
import ai.argvid.gen0.media.db.SessionEntity
import java.time.Instant
import java.util.UUID

class RoomMomentCatalog(
    private val database: Gen0Database,
    private val sessionId: String,
    private val sessionStartedAt: String,
    private val now: () -> Instant = Instant::now,
    private val nextId: () -> String = { UUID.randomUUID().toString() },
) : MomentCatalog {
    override suspend fun markStagingCleaned(reference: SavedMomentReference) {
        database.momentDao().markStagingCleaned(reference.uri)
    }
    override suspend fun insert(record: MomentRecord) {
        database.sessionDao().insertIfAbsent(
            SessionEntity(
                id = sessionId,
                startedAt = sessionStartedAt,
                endedAt = null,
                effectiveDurationUs = 0,
                terminalState = null,
            ),
        )
        database.momentDao().insert(
            record.toEntity(sessionId, nextId(), now().toString()),
        )
    }
}

internal fun MomentRecord.toEntity(sessionId: String, id: String, createdAt: String) =
    MomentEntity(
        id = id,
        sessionId = sessionId,
        source = "manual_rescue",
        qualityTier = qualityTier.name,
        mediaUri = reference.uri,
        durationUs = durationUs,
        createdAt = createdAt,
        status = if (stagingPath == null) MomentDbStatus.SAVED else MomentDbStatus.SAVED_WITH_CLEANUP_PENDING,
        viewedAt = null,
        stagingPath = stagingPath,
        localDeletionReceiptAt = null,
    )
