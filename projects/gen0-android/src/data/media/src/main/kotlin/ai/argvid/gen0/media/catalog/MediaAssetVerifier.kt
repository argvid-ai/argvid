package ai.argvid.gen0.media.catalog

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AssetError {
    PermissionTemporarilyUnavailable,
    ProviderTemporarilyUnavailable,
}

sealed interface AssetCheck {
    data object Present : AssetCheck
    data object NotFound : AssetCheck
    data class Retryable(val error: AssetError) : AssetCheck
}

fun interface AssetVerifier {
    suspend fun check(uri: String): AssetCheck
}

class ContentResolverAssetVerifier(
    private val resolver: ContentResolver,
) : AssetVerifier {
    override suspend fun check(uri: String): AssetCheck = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        try {
            resolver.openFileDescriptor(parsed, "r")?.use { }
                ?: return@withContext AssetCheck.Retryable(AssetError.ProviderTemporarilyUnavailable)
            AssetCheck.Present
        } catch (_: SecurityException) {
            AssetCheck.Retryable(AssetError.PermissionTemporarilyUnavailable)
        } catch (_: FileNotFoundException) {
            confirmAbsent(parsed)
        } catch (_: RuntimeException) {
            AssetCheck.Retryable(AssetError.ProviderTemporarilyUnavailable)
        }
    }

    private fun confirmAbsent(uri: Uri): AssetCheck = try {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                AssetCheck.Retryable(AssetError.ProviderTemporarilyUnavailable)
            } else {
                AssetCheck.NotFound
            }
        } ?: AssetCheck.Retryable(AssetError.ProviderTemporarilyUnavailable)
    } catch (_: SecurityException) {
        AssetCheck.Retryable(AssetError.PermissionTemporarilyUnavailable)
    } catch (_: RuntimeException) {
        AssetCheck.Retryable(AssetError.ProviderTemporarilyUnavailable)
    }
}
