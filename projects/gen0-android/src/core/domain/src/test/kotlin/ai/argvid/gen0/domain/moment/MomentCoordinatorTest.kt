package ai.argvid.gen0.domain.moment

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentCoordinatorTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun confirmedStartWaitsForAnAlreadyStartedSaveBeforeClearingItsState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val harness = Harness(saveGate = gate)
        val saving = async { harness.coordinator.captureRescue(15_000_000) }
        runCurrent()
        assertEquals(MomentState.Saving(QualityTier.Proxy), harness.coordinator.state.value)
        harness.coordinator.onStop()
        val restart = async { harness.coordinator.beginSession() }
        runCurrent()
        assertTrue(!restart.isCompleted)
        gate.complete(Unit)
        assertEquals(MomentState.Saved(QualityTier.Proxy), saving.await().state)
        restart.await()
        assertEquals(MomentState.AssetMissing, harness.coordinator.state.value)
        assertEquals(1, harness.catalog.records.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun confirmedStartWaitsForStoppedEncodingAndDoesNotSaveItsOldOutput() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val harness = Harness(encodeStarted = started, encodeGate = gate)
        val oldCapture = async { harness.coordinator.captureRescue(15_000_000) }
        started.await()
        harness.coordinator.onStop()
        val restart = async { harness.coordinator.beginSession() }
        runCurrent()
        assertTrue(!restart.isCompleted)
        gate.complete(Unit)
        assertEquals(MomentFailure.Stopped, oldCapture.await().failure)
        restart.await()
        assertEquals(MomentState.AssetMissing, harness.coordinator.state.value)
        assertEquals(0, harness.saver.saveCalls)
        assertEquals(MomentState.Saved(QualityTier.Proxy), harness.coordinator.captureRescue(30_000_000).state)
        assertEquals(1, harness.saver.saveCalls)
    }

    @Test
    fun restartRetainsPendingCleanupAndCannotOverwriteItWithAnotherRescue() = runTest {
        val harness = Harness(discardFailures = 1)
        harness.coordinator.captureRescue(15_000_000)
        harness.coordinator.onStop()
        harness.coordinator.beginSession()
        assertEquals(MomentFailure.CleanupFailed, harness.coordinator.captureRescue(30_000_000).failure)
        assertEquals(1, harness.encoder.encodeCalls)
        assertEquals(null, harness.coordinator.retryCleanup().failure)
        assertEquals(listOf(SavedMomentReference("content://moment/1")), harness.catalog.cleaned)
    }

    @Test
    fun insufficientCoverageDoesNotEncode() = runTest {
        val harness = Harness(completeCoverage = false)

        val result = harness.coordinator.captureRescue(15_000_000)

        assertEquals(MomentFailure.InsufficientCoverage, result.failure)
        assertEquals(MomentState.AssetMissing, result.state)
        assertEquals(0, harness.encoder.encodeCalls)
    }

    @Test
    fun encodeFailureIsReportedWithoutSaving() = runTest {
        val harness = Harness(encodeFailure = true)

        val result = harness.coordinator.captureRescue(15_000_000)

        assertEquals(MomentFailure.EncodeFailed, result.failure)
        assertEquals(MomentState.AssetMissing, result.state)
        assertEquals(0, harness.saver.saveCalls)
    }

    @Test
    fun saveFailureRetainsSameEncodedMomentForRetry() = runTest {
        val harness = Harness(saveFailures = 1)

        val failed = harness.coordinator.captureRescue(15_000_000)
        val saved = harness.coordinator.retrySaving()

        assertEquals(MomentFailure.SaveFailed, failed.failure)
        assertEquals(MomentState.SaveFailed, failed.state)
        assertEquals(null, saved.failure)
        assertEquals(MomentState.Saved(QualityTier.Proxy), saved.state)
        assertEquals(1, harness.encoder.encodeCalls)
        assertEquals(2, harness.saver.saveCalls)
        assertSame(harness.saver.received[0], harness.saver.received[1])
        assertEquals(1, harness.catalog.records.size)
        assertEquals("staged.mp4", harness.catalog.records.single().stagingPath)
    }

    @Test
    fun catalogFailureRetainsPublishedReferenceForCatalogOnlyRetry() = runTest {
        val harness = Harness(catalogFailures = 1)

        val failed = harness.coordinator.captureRescue(15_000_000)

        assertNotNull(failed.failure)
        assertFalse(failed.state is MomentState.Saving)
        assertFalse(failed.state is MomentState.Saved)
        assertEquals(SavedMomentReference("content://moment/1"), failed.reference)
        assertEquals(0, harness.encoder.discardCalls)
        assertTrue(harness.catalog.records.isEmpty())
        assertTrue(harness.catalog.cleaned.isEmpty())

        harness.coordinator.onStop()
        val saved = harness.coordinator.retrySaving()

        assertEquals(null, saved.failure)
        assertEquals(MomentState.Saved(QualityTier.Proxy), saved.state)
        assertEquals(failed.reference, saved.reference)
        assertEquals(1, harness.encoder.encodeCalls)
        assertEquals(1, harness.saver.saveCalls)
        assertEquals(2, harness.catalog.insertCalls)
        assertEquals(1, harness.encoder.discardCalls)
        assertEquals(failed.reference, harness.catalog.records.single().reference)
        assertEquals("staged.mp4", harness.catalog.records.single().stagingPath)
        assertEquals(listOf(failed.reference), harness.catalog.cleaned)
    }

    @Test
    fun abandonCannotDiscardPublishedMomentAwaitingCatalogRecovery() = runTest {
        val harness = Harness(catalogFailures = 1)
        val failed = harness.coordinator.captureRescue(15_000_000)

        val abandoned = harness.coordinator.abandon()

        assertNotNull(abandoned.failure)
        assertEquals(failed.state, abandoned.state)
        assertEquals(SavedMomentReference("content://moment/1"), abandoned.reference)
        assertEquals(0, harness.encoder.discardCalls)
        assertEquals(null, harness.coordinator.retrySaving().failure)
        assertEquals(1, harness.saver.saveCalls)
        assertEquals(1, harness.catalog.records.size)
    }

    @Test
    fun stopAndRestartRetainCatalogRecoveryAndRejectAnotherRescue() = runTest {
        val harness = Harness(catalogFailures = 1)
        val failed = harness.coordinator.captureRescue(15_000_000)
        harness.coordinator.onStop()
        harness.coordinator.beginSession()

        assertEquals(failed.state, harness.coordinator.state.value)
        assertNotNull(harness.coordinator.captureRescue(30_000_000).failure)
        assertEquals(0, harness.encoder.discardCalls)
        val recovered = harness.coordinator.retrySaving()
        assertEquals(null, recovered.failure)
        assertEquals(SavedMomentReference("content://moment/1"), recovered.reference)
        assertEquals(1, harness.encoder.encodeCalls)
        assertEquals(1, harness.saver.saveCalls)
        assertEquals("staged.mp4", harness.catalog.records.single().stagingPath)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingCatalogInsertRetainsPublishedIdentityForRecovery() = runTest {
        val gate = CompletableDeferred<Unit>()
        val harness = Harness(catalogGate = gate)
        val capture = async { harness.coordinator.captureRescue(15_000_000) }
        runCurrent()
        assertEquals(1, harness.catalog.insertCalls)

        capture.cancelAndJoin()

        assertFalse(harness.coordinator.state.value is MomentState.Saving)
        assertFalse(harness.coordinator.state.value is MomentState.Saved)
        assertEquals(0, harness.encoder.discardCalls)
        val abandoned = harness.coordinator.abandon()
        assertNotNull(abandoned.failure)
        assertEquals(SavedMomentReference("content://moment/1"), abandoned.reference)
        gate.complete(Unit)
        harness.coordinator.onStop()
        harness.coordinator.beginSession()
        assertEquals(null, harness.coordinator.retrySaving().failure)
        assertEquals(1, harness.encoder.encodeCalls)
        assertEquals(1, harness.saver.saveCalls)
        assertEquals("staged.mp4", harness.catalog.records.single().stagingPath)
    }

    @Test
    fun secondCaptureIsRejectedWhileEncoding() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val harness = Harness(encodeStarted = started, encodeGate = gate)
        val first = async { harness.coordinator.captureRescue(15_000_000) }
        started.await()

        val overlapping = harness.coordinator.captureRescue(15_000_000)
        gate.complete(Unit)
        val completed = first.await()

        assertEquals(MomentFailure.CaptureInProgress, overlapping.failure)
        assertEquals(MomentState.Encoding(QualityTier.Proxy), overlapping.state)
        assertEquals(MomentState.Saved(QualityTier.Proxy), completed.state)
        assertEquals(1, harness.encoder.encodeCalls)
    }

    @Test
    fun abandonDiscardsFailedSave() = runTest {
        val harness = Harness(saveFailures = 1)
        harness.coordinator.captureRescue(15_000_000)

        val result = harness.coordinator.abandon()

        assertEquals(MomentState.Deleted, result.state)
        assertEquals(1, harness.encoder.discardCalls)
        assertEquals(0, harness.catalog.records.size)
    }

    @Test
    fun cleanupFailureCanBeRetriedWithoutEncodingOrSavingAgain() = runTest {
        val harness = Harness(discardFailures = 1)

        val saved = harness.coordinator.captureRescue(15_000_000)
        val cleaned = harness.coordinator.retryCleanup()

        assertEquals(MomentFailure.CleanupFailed, saved.failure)
        assertEquals(MomentState.Saved(QualityTier.Proxy), saved.state)
        assertEquals(null, cleaned.failure)
        assertEquals(1, harness.encoder.encodeCalls)
        assertEquals(1, harness.saver.saveCalls)
        assertEquals(2, harness.encoder.discardCalls)
        assertEquals(1, harness.catalog.records.size)
    }

    @Test
    fun stopDuringEncodeDiscardsOwnedOutputInsteadOfSaving() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val harness = Harness(encodeStarted = started, encodeGate = gate)
        val capture = async { harness.coordinator.captureRescue(15_000_000) }
        started.await()

        harness.coordinator.onStop()
        gate.complete(Unit)
        val result = capture.await()

        assertEquals(MomentFailure.Stopped, result.failure)
        assertEquals(MomentState.Deleted, result.state)
        assertEquals(0, harness.saver.saveCalls)
        assertEquals(1, harness.encoder.discardCalls)
    }

    @Test
    fun ownedSnapshotCannotBeChangedBySourceAfterCaptureStarts() = runTest {
        val source = FakeMomentSource(completeCoverage = true)
        val harness = Harness(source = source)

        harness.coordinator.captureRescue(15_000_000)
        source.bytes[0] = 99

        assertTrue(harness.encoder.received.single().frames.single().jpeg.contentEquals(byteArrayOf(1, 2, 3)))
    }
}

private class Harness(
    completeCoverage: Boolean = true,
    encodeFailure: Boolean = false,
    saveFailures: Int = 0,
    discardFailures: Int = 0,
    encodeStarted: CompletableDeferred<Unit>? = null,
    encodeGate: CompletableDeferred<Unit>? = null,
    source: FakeMomentSource = FakeMomentSource(completeCoverage),
    saveGate: CompletableDeferred<Unit>? = null,
    catalogFailures: Int = 0,
    catalogGate: CompletableDeferred<Unit>? = null,
) {
    val encoder = FakeMomentEncoder(encodeFailure, discardFailures, encodeStarted, encodeGate)
    val saver = FakeMomentSaver(saveFailures, saveGate)
    val catalog = FakeMomentCatalog(catalogFailures, catalogGate)
    val coordinator = MomentCoordinator(source, encoder, saver, catalog)
}

private class FakeMomentSource(
    private val completeCoverage: Boolean,
) : MomentRescueSource {
    val bytes = byteArrayOf(1, 2, 3)

    override suspend fun ownedMomentSnapshot(endingAtUs: Long, lookbackUs: Long) = OwnedRescueAsset(
        frames = listOf(RescueFrame(0, 960, 540, bytes.copyOf())),
        requestStartUs = endingAtUs - lookbackUs,
        requestEndUs = endingAtUs,
        coverageComplete = completeCoverage,
        qualityTier = QualityTier.Proxy,
    )
}

private class FakeMomentEncoder(
    private val encodeFailure: Boolean,
    discardFailures: Int,
    private val encodeStarted: CompletableDeferred<Unit>?,
    private val encodeGate: CompletableDeferred<Unit>?,
) : MomentEncoder {
    var encodeCalls = 0
    var discardCalls = 0
    var remainingDiscardFailures = discardFailures
    val received = mutableListOf<OwnedRescueAsset>()

    override suspend fun encode(asset: OwnedRescueAsset): EncodedMoment {
        encodeCalls += 1
        received += asset
        encodeStarted?.complete(Unit)
        encodeGate?.await()
        if (encodeFailure) error("encode")
        return EncodedMoment("staged.mp4", 15_000_000, 960, 540, 0, asset.qualityTier)
    }

    override suspend fun discard(moment: EncodedMoment) {
        discardCalls += 1
        if (remainingDiscardFailures > 0) {
            remainingDiscardFailures -= 1
            error("discard")
        }
    }
}

private class FakeMomentSaver(saveFailures: Int, private val gate: CompletableDeferred<Unit>?) : MomentSaver {
    var saveCalls = 0
    var remainingFailures = saveFailures
    val received = mutableListOf<EncodedMoment>()

    override suspend fun save(moment: EncodedMoment): SavedMomentReference {
        saveCalls += 1
        received += moment
        gate?.await()
        if (remainingFailures > 0) {
            remainingFailures -= 1
            error("save")
        }
        return SavedMomentReference("content://moment/1")
    }
}

private class FakeMomentCatalog(
    private var remainingFailures: Int,
    private val gate: CompletableDeferred<Unit>?,
) : MomentCatalog {
    val records = mutableListOf<MomentRecord>()
    val cleaned = mutableListOf<SavedMomentReference>()
    var insertCalls = 0
    override suspend fun markStagingCleaned(reference: SavedMomentReference) { cleaned += reference }
    override suspend fun insert(record: MomentRecord) {
        insertCalls += 1
        gate?.await()
        if (remainingFailures > 0) {
            remainingFailures -= 1
            error("catalog insert")
        }
        records += record
    }
}
