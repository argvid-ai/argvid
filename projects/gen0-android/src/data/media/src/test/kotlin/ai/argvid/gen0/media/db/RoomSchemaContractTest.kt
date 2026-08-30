package ai.argvid.gen0.media.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSchemaContractTest {
    @Test
    fun momentRowsContainMetadataButNoMediaOrSecrets() {
        val columns = MomentEntity::class.java.declaredFields.map { it.name }.toSet()

        assertTrue(
            setOf(
                "id", "sessionId", "source", "qualityTier", "mediaUri", "durationUs",
                "createdAt", "status", "viewedAt",
            ).all(columns::contains),
        )
        assertFalse(
            setOf("bytes", "apiKey", "deleteCredential", "freeTextReason")
                .any(columns::contains),
        )
    }
}
