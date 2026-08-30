package ai.argvid.gen0.media.db

import android.content.Context
import ai.argvid.gen0.domain.moment.*
import ai.argvid.gen0.media.catalog.RoomMomentCatalog
import ai.argvid.gen0.media.delete.*
import java.io.File
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalDatabaseTest {
    @Test
    fun failedEncoderCleanupIsPersistedAndTodayDeletionRemovesTheRealStagedFile() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, Gen0Database::class.java).build()
        val staged = File.createTempFile("synthetic-staging-", ".mp4", context.cacheDir)
        try {
            staged.writeText("synthetic staged bytes")
            val coordinator = MomentCoordinator(
                source = object : MomentRescueSource {
                    override suspend fun ownedMomentSnapshot(endingAtUs: Long, lookbackUs: Long) =
                        OwnedRescueAsset(emptyList(), 0, lookbackUs, true, QualityTier.Proxy)
                },
                encoder = object : MomentEncoder {
                    override suspend fun encode(asset: OwnedRescueAsset) =
                        EncodedMoment(staged.path, 15_000_000, 960, 540, 0, QualityTier.Proxy)
                    override suspend fun discard(moment: EncodedMoment) { error("cleanup unavailable") }
                },
                saver = MomentSaver { SavedMomentReference("content://media/1") },
                catalog = RoomMomentCatalog(database, "s1", "now", nextId = { "m1" }),
            )
            assertEquals(MomentFailure.CleanupFailed, coordinator.captureRescue(15_000_000).failure)
            assertTrue(staged.exists())
            assertEquals(staged.path, database.momentDao().get("m1")?.stagingPath)
            assertEquals(MomentDbStatus.SAVED_WITH_CLEANUP_PENDING, database.momentDao().get("m1")?.status)
            val receipt = LocalDeletionCoordinator(RoomLocalDeletionStore(database.momentDao()),
                LocalMediaDeleter { DeleteAssetResult.Deleted }, AppPrivateStagingStore(context.cacheDir),
                now = { "receipt" }).deleteLocal("m1")
            assertEquals(LocalDeletionState.COMPLETE, receipt.localState)
            assertFalse(staged.exists())
            assertEquals("receipt", database.momentDao().get("m1")?.localDeletionReceiptAt)
            assertEquals(0, database.momentDao().markStagingCleaned("content://media/1"))
            assertEquals(MomentDbStatus.DELETED, database.momentDao().get("m1")?.status)
        } finally {
            staged.delete()
            database.close()
        }
    }

    @Test
    fun savedMomentCanBeReadThenDeletedWithALocalReceipt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, Gen0Database::class.java).build()
        try {
            database.sessionDao().insert(SessionEntity("s1", "2026-08-30", null, 0, null))
            val dao = database.momentDao()
            dao.insert(
                MomentEntity(
                    "m1", "s1", "manual_rescue", "Proxy", "content://media/1", 15_000_000,
                    "2026-08-30", MomentDbStatus.SAVED, null, null, null,
                ),
            )
            assertEquals("m1", dao.latestPlayableCandidate()?.id)
            val catalog = RoomMomentCatalog(database, "s1", "now", nextId = { "m2" })
            catalog.insert(MomentRecord(SavedMomentReference("content://media/2"), 15_000_000,
                960, 540, 0, QualityTier.Proxy, "already-discarded.mp4"))
            assertEquals(MomentDbStatus.SAVED_WITH_CLEANUP_PENDING, dao.get("m2")?.status)
            catalog.markStagingCleaned(SavedMomentReference("content://media/2"))
            assertNull(dao.get("m2")?.stagingPath)
            assertEquals(MomentDbStatus.SAVED, dao.get("m2")?.status)
            dao.markLocalDeletePending("m2")
            assertEquals(0, dao.clearDeletedRecord("m1"))
            assertEquals(1, dao.markLocalDeletePending("m1"))
            assertNull(dao.latestPlayableCandidate())
            assertEquals(1, dao.completeLocalDeletion("m1", "2026-08-30T12:00:00Z"))
            assertEquals("2026-08-30T12:00:00Z", dao.get("m1")?.localDeletionReceiptAt)
            assertNull(dao.get("m1")?.mediaUri)
            assertEquals(1, dao.clearDeletedRecord("m1"))
            assertNull(dao.get("m1"))
        } finally {
            database.close()
        }
    }
}
