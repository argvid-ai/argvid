package ai.argvid.gen0.media.delete

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DeleteStagingResult {
    Deleted,
    AlreadyMissing,
    Retryable,
}

fun interface PrivateStagingStore {
    suspend fun delete(path: String): DeleteStagingResult
}

class AppPrivateStagingStore(
    private val root: File,
) : PrivateStagingStore {
    override suspend fun delete(path: String): DeleteStagingResult = withContext(Dispatchers.IO) {
        try {
            val canonicalRoot = root.canonicalFile
            val target = File(path).canonicalFile
            if (target != canonicalRoot && target.parentFile != canonicalRoot) {
                return@withContext DeleteStagingResult.Retryable
            }
            when {
                !target.exists() -> DeleteStagingResult.AlreadyMissing
                target.delete() -> DeleteStagingResult.Deleted
                else -> DeleteStagingResult.Retryable
            }
        } catch (_: IOException) {
            DeleteStagingResult.Retryable
        } catch (_: SecurityException) {
            DeleteStagingResult.Retryable
        }
    }
}
