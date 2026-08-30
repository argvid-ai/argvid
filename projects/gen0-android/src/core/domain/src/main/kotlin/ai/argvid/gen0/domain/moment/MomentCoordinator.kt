package ai.argvid.gen0.domain.moment

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MomentFailure {
    InsufficientCoverage,
    EncodeFailed,
    SaveFailed,
    CleanupFailed,
    CaptureInProgress,
    NoPendingMoment,
    Stopped,
}

data class MomentResult(
    val state: MomentState,
    val failure: MomentFailure? = null,
    val reference: SavedMomentReference? = null,
)

class MomentCoordinator(
    private val source: MomentRescueSource,
    private val encoder: MomentEncoder,
    private val saver: MomentSaver,
    private val catalog: MomentCatalog,
    private val lookbackUs: Long = 15_000_000,
) {
    private val operationMutex = Mutex()
    private val stopped = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<MomentState>(MomentState.AssetMissing)
    private var pendingMoment: EncodedMoment? = null
    private var pendingCleanup: EncodedMoment? = null
    private var cleanupTerminalState: MomentState? = null
    private var savedReference: SavedMomentReference? = null

    val state: StateFlow<MomentState> = mutableState.asStateFlow()

    suspend fun beginSession() = operationMutex.withLock {
        stopped.set(false)
        if (pendingMoment == null && pendingCleanup == null) mutableState.value = MomentState.AssetMissing
    }

    suspend fun captureRescue(nowUs: Long): MomentResult = exclusive {
        if (stopped.get()) return@exclusive result(MomentFailure.Stopped)
        if (pendingCleanup != null) return@exclusive result(MomentFailure.CleanupFailed)
        if (pendingMoment != null) return@exclusive result(MomentFailure.CaptureInProgress)
        savedReference = null
        val asset = source.ownedMomentSnapshot(nowUs, lookbackUs).ownedCopy()
        if (!asset.coverageComplete) {
            mutableState.value = MomentState.AssetMissing
            return@exclusive result(MomentFailure.InsufficientCoverage)
        }

        mutableState.value = MomentState.Encoding(asset.qualityTier)
        val encoded = try {
            encoder.encode(asset)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = MomentState.AssetMissing
            return@exclusive result(MomentFailure.EncodeFailed)
        }

        if (stopped.get()) {
            mutableState.value = MomentState.Deleted
            return@exclusive discardAfterStop(encoded)
        }

        pendingMoment = encoded
        savePendingMoment()
    }

    suspend fun retrySaving(): MomentResult = exclusive {
        if (stopped.get()) return@exclusive result(MomentFailure.Stopped)
        if (pendingMoment == null) return@exclusive result(MomentFailure.NoPendingMoment)
        savePendingMoment()
    }

    suspend fun abandon(): MomentResult = exclusive {
        val pending = pendingMoment ?: return@exclusive result(MomentFailure.NoPendingMoment)
        pendingMoment = null
        mutableState.value = MomentState.Deleted
        try {
            encoder.discard(pending)
            result()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            pendingCleanup = pending
            cleanupTerminalState = MomentState.Deleted
            result(MomentFailure.CleanupFailed)
        }
    }

    suspend fun retryCleanup(): MomentResult = exclusive {
        val pending = pendingCleanup ?: return@exclusive result(MomentFailure.NoPendingMoment)
        try {
            encoder.discard(pending)
            if (cleanupTerminalState is MomentState.Saved) savedReference?.let { catalog.markStagingCleaned(it) }
            pendingCleanup = null
            cleanupTerminalState?.let { mutableState.value = it }
            cleanupTerminalState = null
            result(reference = savedReference)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result(MomentFailure.CleanupFailed, savedReference)
        }
    }

    fun onStop() {
        stopped.set(true)
    }

    private suspend fun savePendingMoment(): MomentResult {
        val pending = checkNotNull(pendingMoment)
        mutableState.value = MomentState.Saving(pending.qualityTier)
        val saved = try {
            saver.save(pending)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = MomentState.SaveFailed
            return result(MomentFailure.SaveFailed)
        }

        catalog.insert(pending.toRecord(saved))
        savedReference = saved
        pendingMoment = null
        mutableState.value = MomentState.Saved(pending.qualityTier)
        return try {
            encoder.discard(pending)
            catalog.markStagingCleaned(saved)
            result(reference = saved)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            pendingCleanup = pending
            cleanupTerminalState = mutableState.value
            result(MomentFailure.CleanupFailed, saved)
        }
    }

    private suspend fun discardAfterStop(encoded: EncodedMoment): MomentResult = try {
        encoder.discard(encoded)
        result(MomentFailure.Stopped)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        pendingCleanup = encoded
        cleanupTerminalState = MomentState.Deleted
        result(MomentFailure.CleanupFailed)
    }

    private suspend fun exclusive(block: suspend () -> MomentResult): MomentResult {
        if (!operationMutex.tryLock()) return result(MomentFailure.CaptureInProgress)
        return try {
            block()
        } finally {
            operationMutex.unlock()
        }
    }

    private fun result(
        failure: MomentFailure? = null,
        reference: SavedMomentReference? = null,
    ) = MomentResult(mutableState.value, failure, reference)

    private fun OwnedRescueAsset.ownedCopy() = copy(
        frames = frames.map { it.copy(jpeg = it.jpeg.copyOf()) },
    )

    private fun EncodedMoment.toRecord(reference: SavedMomentReference) = MomentRecord(
        reference = reference,
        durationUs = durationUs,
        width = width,
        height = height,
        rotationDegrees = rotationDegrees,
        qualityTier = qualityTier,
        stagingPath = stagingPath,
    )
}
