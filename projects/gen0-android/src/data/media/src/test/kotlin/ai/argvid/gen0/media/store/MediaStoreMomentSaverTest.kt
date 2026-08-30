package ai.argvid.gen0.media.store

import ai.argvid.gen0.domain.moment.EncodedMoment
import ai.argvid.gen0.domain.moment.QualityTier
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreMomentSaverTest {
    @Test
    fun successInsertsPendingCopiesPublishesThenVerifies() = runTest {
        val client = FakeMediaStoreClient()
        val saver = saver(client)

        val reference = saver.save(encoded())

        assertEquals("content://media/moment-1", reference.uri)
        assertEquals(
            listOf(
                "insert:GEN0_20260829_133045_abc123.mp4:Movies/Gen0Camera:video/mp4",
                "copy:staged.mp4:content://media/moment-1",
                "publish:content://media/moment-1",
                "read:content://media/moment-1",
            ),
            client.events,
        )
    }

    @Test
    fun copyFailureDeletesPendingRow() = runTest {
        val client = FakeMediaStoreClient(failAt = "copy")

        val failure = assertSaveFailure(MediaStoreSaveFailure.CopyFailed) { saver(client).save(encoded()) }

        assertTrue(failure.cause is IllegalStateException)
        assertEquals("delete:content://media/moment-1", client.events.last())
    }

    @Test
    fun publishFailureDeletesPendingRow() = runTest {
        val client = FakeMediaStoreClient(failAt = "publish")

        assertSaveFailure(MediaStoreSaveFailure.PublishFailed) { saver(client).save(encoded()) }

        assertEquals("delete:content://media/moment-1", client.events.last())
    }

    @Test
    fun unreadablePublishedRowIsDeletedAndNeverReturned() = runTest {
        val client = FakeMediaStoreClient(canRead = false)

        assertSaveFailure(MediaStoreSaveFailure.VerifyFailed) { saver(client).save(encoded()) }

        assertEquals("delete:content://media/moment-1", client.events.last())
    }

    @Test
    fun permissionFailureIsMappedSeparately() = runTest {
        val client = FakeMediaStoreClient(failAt = "insert-permission")

        assertSaveFailure(MediaStoreSaveFailure.PermissionDenied) { saver(client).save(encoded()) }

        assertEquals(1, client.events.size)
    }

    @Test
    fun permissionFailureAfterInsertStillDeletesPendingRow() = runTest {
        val client = FakeMediaStoreClient(failAt = "copy-permission")

        assertSaveFailure(MediaStoreSaveFailure.PermissionDenied) { saver(client).save(encoded()) }

        assertEquals("delete:content://media/moment-1", client.events.last())
    }

    private fun saver(client: MediaStoreClient) = MediaStoreMomentSaver(
        client = client,
        clock = Clock.fixed(Instant.parse("2026-08-29T13:30:45Z"), ZoneOffset.UTC),
        nextMomentId = { "abc123" },
    )

    private fun encoded() = EncodedMoment(
        stagingPath = File("staged.mp4").path,
        durationUs = 15_000_000,
        width = 960,
        height = 540,
        rotationDegrees = 0,
        qualityTier = QualityTier.Proxy,
    )

    private suspend fun assertSaveFailure(
        expected: MediaStoreSaveFailure,
        block: suspend () -> Unit,
    ): MediaStoreSaveException = try {
        block()
        throw AssertionError("Expected MediaStoreSaveException")
    } catch (failure: MediaStoreSaveException) {
        assertEquals(expected, failure.reason)
        failure
    }
}

private class FakeMediaStoreClient(
    private val failAt: String? = null,
    private val canRead: Boolean = true,
) : MediaStoreClient {
    val events = mutableListOf<String>()
    private val target = MediaStoreTarget("content://media/moment-1")

    override suspend fun insertPending(displayName: String, relativePath: String, mime: String): MediaStoreTarget {
        events += "insert:$displayName:$relativePath:$mime"
        if (failAt == "insert-permission") throw SecurityException("permission")
        if (failAt == "insert") error("insert")
        return target
    }

    override suspend fun copyFrom(source: File, target: MediaStoreTarget) {
        events += "copy:${source.path}:${target.value}"
        if (failAt == "copy-permission") throw SecurityException("permission")
        if (failAt == "copy") error("copy")
    }

    override suspend fun publish(target: MediaStoreTarget) {
        events += "publish:${target.value}"
        if (failAt == "publish") error("publish")
    }

    override suspend fun canRead(target: MediaStoreTarget): Boolean {
        events += "read:${target.value}"
        return canRead
    }

    override suspend fun delete(target: MediaStoreTarget): Int {
        events += "delete:${target.value}"
        return 1
    }
}
