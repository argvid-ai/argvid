package ai.argvid.gen0.media.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val startedAt: String,
    val endedAt: String?,
    val effectiveDurationUs: Long,
    val terminalState: String?,
)
