package ai.argvid.gen0.media.delete

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateStagingStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun deletesOnlyFilesDirectlyInsideConfiguredPrivateRoot() = runTest {
        val root = temporary.newFolder("staging")
        val staged = root.resolve("moment.mp4").apply { writeBytes(byteArrayOf(1)) }
        val outside = temporary.newFile("outside.mp4").apply { writeBytes(byteArrayOf(2)) }
        val store = AppPrivateStagingStore(root)

        assertEquals(DeleteStagingResult.Deleted, store.delete(staged.path))
        assertEquals(DeleteStagingResult.Retryable, store.delete(outside.path))
        assertFalse(staged.exists())
        assertTrue(outside.exists())
    }
}
