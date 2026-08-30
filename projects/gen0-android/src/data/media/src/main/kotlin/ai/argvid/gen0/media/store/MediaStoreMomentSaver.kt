package ai.argvid.gen0.media.store

import ai.argvid.gen0.domain.moment.EncodedMoment
import ai.argvid.gen0.domain.moment.MomentSaver
import ai.argvid.gen0.domain.moment.SavedMomentReference
import java.io.File
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class MediaStoreSaveFailure {
    StorageUnavailable,
    PermissionDenied,
    CopyFailed,
    PublishFailed,
    VerifyFailed,
}

class MediaStoreSaveException(
    val reason: MediaStoreSaveFailure,
    cause: Throwable? = null,
) : IllegalStateException(reason.name, cause)

class MediaStoreMomentSaver(
    private val client: MediaStoreClient,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val nextMomentId: () -> String = { UUID.randomUUID().toString().substringBefore('-') },
) : MomentSaver {
    override suspend fun save(moment: EncodedMoment): SavedMomentReference {
        val target = try {
            client.insertPending(displayName(), RELATIVE_PATH, MIME_TYPE)
        } catch (failure: SecurityException) {
            throw MediaStoreSaveException(MediaStoreSaveFailure.PermissionDenied, failure)
        } catch (failure: Exception) {
            throw MediaStoreSaveException(MediaStoreSaveFailure.StorageUnavailable, failure)
        }

        try {
            client.copyFrom(File(moment.stagingPath), target)
        } catch (failure: SecurityException) {
            throw cleanupThen(MediaStoreSaveFailure.PermissionDenied, failure, target)
        } catch (failure: Exception) {
            throw cleanupThen(MediaStoreSaveFailure.CopyFailed, failure, target)
        }
        try {
            client.publish(target)
        } catch (failure: SecurityException) {
            throw cleanupThen(MediaStoreSaveFailure.PermissionDenied, failure, target)
        } catch (failure: Exception) {
            throw cleanupThen(MediaStoreSaveFailure.PublishFailed, failure, target)
        }
        val readable = try {
            client.canRead(target)
        } catch (failure: SecurityException) {
            throw cleanupThen(MediaStoreSaveFailure.PermissionDenied, failure, target)
        } catch (failure: Exception) {
            throw cleanupThen(MediaStoreSaveFailure.VerifyFailed, failure, target)
        }
        if (!readable) throw cleanupThen(MediaStoreSaveFailure.VerifyFailed, null, target)
        return SavedMomentReference(target.value)
    }

    private suspend fun cleanupThen(
        reason: MediaStoreSaveFailure,
        cause: Throwable?,
        target: MediaStoreTarget,
    ): MediaStoreSaveException {
        val failure = MediaStoreSaveException(reason, cause)
        try {
            client.delete(target)
        } catch (cleanupFailure: Exception) {
            failure.addSuppressed(cleanupFailure)
        }
        return failure
    }

    private fun displayName(): String {
        val timestamp = FILE_TIMESTAMP.format(clock.instant().atZone(clock.zone))
        return "GEN0_${timestamp}_${nextMomentId()}.mp4"
    }

    private companion object {
        val FILE_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        const val RELATIVE_PATH = "Movies/Gen0Camera"
        const val MIME_TYPE = "video/mp4"
    }
}
