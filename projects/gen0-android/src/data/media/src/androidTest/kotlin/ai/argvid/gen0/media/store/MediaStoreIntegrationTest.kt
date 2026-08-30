package ai.argvid.gen0.media.store

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.argvid.gen0.domain.moment.OwnedRescueAsset
import ai.argvid.gen0.domain.moment.QualityTier
import ai.argvid.gen0.domain.moment.RescueFrame
import ai.argvid.gen0.media.codec.AndroidProxyMovieEncoder
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreIntegrationTest {
    @Test
    fun publishedUriIsReadableAndCanBeDeleted() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val encoder = AndroidProxyMovieEncoder(File(context.cacheDir, "media-store-encoder"))
        val encoded = encoder.encode(asset(context))
        val client = ContentResolverMediaStoreClient(context.contentResolver)
        val saver = MediaStoreMomentSaver(client, nextMomentId = { "integration" })

        val reference = saver.save(encoded)
        val target = MediaStoreTarget(reference.uri)
        assertTrue(client.canRead(target))

        val extractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(android.net.Uri.parse(reference.uri), "r")!!.use { descriptor ->
            extractor.setDataSource(descriptor.fileDescriptor)
        }
        val mime = (0 until extractor.trackCount)
            .map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            .first { it?.startsWith("video/") == true }
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, mime)
        extractor.release()

        assertEquals(1, client.delete(target))
        assertFalse(client.canRead(target))
        encoder.discard(encoded)
    }

    private fun asset(context: Context): OwnedRescueAsset {
        val names = context.assets.list("proxy-frames").orEmpty()
            .filter { it.endsWith(".jpg") }
            .sorted()
        return OwnedRescueAsset(
            frames = names.mapIndexed { index, name ->
                RescueFrame(
                    timestampUs = index * 125_000L,
                    width = 960,
                    height = 540,
                    jpeg = context.assets.open("proxy-frames/$name").use { it.readBytes() },
                )
            },
            requestStartUs = 0,
            requestEndUs = 2_000_000,
            coverageComplete = true,
            qualityTier = QualityTier.Proxy,
        )
    }
}
