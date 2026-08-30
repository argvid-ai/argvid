package ai.argvid.gen0.media.store

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@JvmInline
value class MediaStoreTarget(val value: String)

interface MediaStoreClient {
    suspend fun insertPending(displayName: String, relativePath: String, mime: String): MediaStoreTarget
    suspend fun copyFrom(source: File, target: MediaStoreTarget)
    suspend fun publish(target: MediaStoreTarget)
    suspend fun canRead(target: MediaStoreTarget): Boolean
    suspend fun delete(target: MediaStoreTarget): Int
}

class ContentResolverMediaStoreClient(
    private val resolver: ContentResolver,
) : MediaStoreClient {
    override suspend fun insertPending(
        displayName: String,
        relativePath: String,
        mime: String,
    ): MediaStoreTarget = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        MediaStoreTarget(requireNotNull(resolver.insert(collection, values)).toString())
    }

    override suspend fun copyFrom(source: File, target: MediaStoreTarget) = withContext(Dispatchers.IO) {
        require(source.isFile)
        source.inputStream().use { input ->
            requireNotNull(resolver.openOutputStream(target.uri(), "w")).use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
    }

    override suspend fun publish(target: MediaStoreTarget) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        check(resolver.update(target.uri(), values, null, null) == 1) { "Unable to publish MediaStore row" }
    }

    override suspend fun canRead(target: MediaStoreTarget): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.openFileDescriptor(target.uri(), "r")?.use { descriptor -> descriptor.statSize >= 0 } == true
        } catch (_: FileNotFoundException) {
            false
        }
    }

    override suspend fun delete(target: MediaStoreTarget): Int = withContext(Dispatchers.IO) {
        resolver.delete(target.uri(), null, null)
    }

    private fun MediaStoreTarget.uri(): Uri = Uri.parse(value)
}
