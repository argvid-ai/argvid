package ai.argvid.gen0.media.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MomentDbStatus {
    SAVED,
    SAVED_WITH_CLEANUP_PENDING,
    ASSET_MISSING,
    LOCAL_DELETE_PENDING,
    DELETED,
}

@Entity(
    tableName = "moments",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("createdAt"), Index("status")],
)
data class MomentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val source: String,
    val qualityTier: String,
    val mediaUri: String?,
    val durationUs: Long,
    val createdAt: String,
    val status: MomentDbStatus,
    val viewedAt: String?,
    val stagingPath: String?,
    val localDeletionReceiptAt: String?,
)
