package ai.argvid.gen0.session

import ai.argvid.gen0.domain.capture.CaptureStopResult
import ai.argvid.gen0.domain.capture.CaptureBufferPort
import ai.argvid.gen0.domain.capture.CaptureGimbalPort
import ai.argvid.gen0.domain.capture.CapturePreviewPort
import ai.argvid.gen0.domain.capture.CaptureSamplerPort
import ai.argvid.gen0.domain.capture.CaptureSessionController
import ai.argvid.gen0.domain.detection.AutomaticRecordingDecision
import ai.argvid.gen0.domain.detection.SubjectLabel
import ai.argvid.gen0.domain.detection.SubjectObservation
import ai.argvid.gen0.domain.moment.EncodedMoment
import ai.argvid.gen0.domain.moment.MomentCatalog
import ai.argvid.gen0.domain.moment.MomentCoordinator
import ai.argvid.gen0.domain.moment.MomentEncoder
import ai.argvid.gen0.domain.moment.MomentRescueSource
import ai.argvid.gen0.domain.moment.MomentSaver
import ai.argvid.gen0.domain.moment.OwnedRescueAsset
import ai.argvid.gen0.domain.moment.RescueFrame
import ai.argvid.gen0.domain.moment.SavedMomentReference
import ai.argvid.gen0.domain.capture.StopReason
import ai.argvid.gen0.domain.gimbal.GimbalConnectionState
import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.gimbal.GimbalTelemetry
import ai.argvid.gen0.domain.moment.MomentFailure
import ai.argvid.gen0.domain.moment.MomentResult
import ai.argvid.gen0.domain.moment.MomentState
import ai.argvid.gen0.domain.moment.QualityTier
import ai.argvid.gen0.domain.session.PauseReason
import ai.argvid.gen0.domain.session.SessionState
import ai.argvid.gen0.domain.time.MonotonicClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    @Test
    fun deniedCapturePermissionsAreVisibleEvenAfterAnEarlierSave() = runTest {
        val model = viewModel(moments = FakeSessionMoments())
        model.onAction(SessionAction.Rescue)
        runCurrent()
        assertTrue(model.uiState.value.showSaved)
        model.onAction(SessionAction.Stop)
        runCurrent()
        model.onAction(SessionAction.StartPreflight)
        model.onPermissionResult(AppPermission.Camera, false)
        runCurrent()
        assertTrue(model.uiState.value.statusText.contains("麦克风权限未授予"))
        assertFalse(model.uiState.value.rescueEnabled)
    }

    @Test
    fun microphoneFailureStopsCaptureAndRemainsVisibleDuringWarmupUpdates() = runTest {
        val capture = FakeSessionCapture()
        val model = viewModel(capture)
        model.onCaptureFailure("麦克风被系统静音")
        runCurrent()
        model.onWarmupProgress(15_000_000)
        assertEquals(SessionState.Paused(PauseReason.UserStop), capture.state.value)
        assertFalse(model.uiState.value.rescueEnabled)
        assertTrue(model.uiState.value.statusText.contains("麦克风被系统静音"))
    }

    @Test
    fun rescueFailureRemainsVisibleWhileWarmupUpdatesContinue() = runTest {
        for (failure in listOf(MomentFailure.InsufficientCoverage, MomentFailure.EncodeFailed)) {
            val viewModel = viewModel(moments = FakeSessionMoments(rescueFailure = failure))
            viewModel.onAction(SessionAction.Rescue)
            runCurrent()
            val message = viewModel.uiState.value.statusText
            assertTrue(message.contains("未保存"))
            viewModel.onWarmupProgress(14_900_000)
            runCurrent()
            assertEquals(message, viewModel.uiState.value.statusText)
            assertFalse(viewModel.uiState.value.showSaved)
        }
    }

    @Test
    fun rescueImmediatelyDisablesTheButtonAndIgnoresRapidDuplicateActions() = runTest {
        val gate = CompletableDeferred<Unit>()
        val moments = FakeSessionMoments(saveGate = gate)
        val viewModel = viewModel(moments = moments)
        viewModel.onAction(SessionAction.Rescue)
        assertFalse(viewModel.uiState.value.rescueEnabled)
        viewModel.onAction(SessionAction.Rescue)
        runCurrent()
        assertEquals(1, moments.rescueCalls)
        gate.complete(Unit)
        runCurrent()
        assertTrue(viewModel.uiState.value.showSaved)
        assertTrue(viewModel.uiState.value.rescueEnabled)
    }

    @Test
    fun backgroundCancelsAStartStillWaitingOnAnEarlierMoment() = runTest {
        val gate = CompletableDeferred<Unit>()
        val capture = FakeSessionCapture()
        val viewModel = viewModel(capture, FakeSessionMoments(beginGate = gate))
        viewModel.onAction(SessionAction.StartPreflight)
        viewModel.onPermissionResult(AppPermission.Camera, true)
        runCurrent()
        viewModel.onAppStopped()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(SessionState.Paused(PauseReason.UserStop), capture.state.value)
        assertTrue(viewModel.uiState.value.resumeConfirmationRequired)
    }

    @Test
    fun startWhileAlreadyRunningDoesNotRequestAnotherBinding() = runTest {
        val viewModel = viewModel()
        viewModel.onAction(SessionAction.StartPreflight)
        viewModel.onPermissionResult(AppPermission.Camera, true)
        runCurrent()
        viewModel.onAction(SessionAction.StartPreflight)
        assertNull(viewModel.uiState.value.permissionRequest)
    }

    @Test
    fun stoppedPermissionRequestIgnoresStaleResultAndCanBeRequestedAgain() = runTest {
        val viewModel = viewModel()
        viewModel.onAction(SessionAction.StartPreflight)
        viewModel.onAppStopped()
        runCurrent()
        viewModel.onPermissionResult(AppPermission.Camera, true)
        runCurrent()
        assertTrue(viewModel.uiState.value.resumeConfirmationRequired)
        viewModel.onAction(SessionAction.ConfirmResume)
        assertEquals(AppPermission.Camera, viewModel.uiState.value.permissionRequest)
    }

    @Test
    fun simulatorDoesNotRequestDevicePermissions() = runTest {
        val viewModel = viewModel()

        viewModel.onAction(SessionAction.ConnectGimbal)

        assertNull(viewModel.uiState.value.permissionRequest)
        assertEquals("仅提供语义模拟器；不连接物理云台", viewModel.uiState.value.gimbalNotice)
    }

    @Test
    fun subjectDetectionUpdatesStateWithoutStartingCaptureOrRescue() = runTest {
        val capture = FakeSessionCapture()
        val moments = FakeSessionMoments()
        val viewModel = viewModel(capture = capture, moments = moments)
        val start = Instant.parse("2026-09-03T00:00:00Z")

        viewModel.onPersonDetectionSensitivityChanged(75)
        viewModel.onFaceDetectionSensitivityChanged(25)
        viewModel.onSubjectObservation(SubjectObservation(setOf(SubjectLabel.PERSON), start))
        viewModel.onSubjectObservation(
            SubjectObservation(setOf(SubjectLabel.PERSON), start.plusSeconds(2)),
        )

        val detection = viewModel.uiState.value.subjectDetection
        assertTrue(detection.detectorAvailable)
        assertEquals(setOf(SubjectLabel.PERSON), detection.labels)
        assertEquals(75, detection.personSensitivity.progress)
        assertEquals(25, detection.faceSensitivity.progress)
        assertEquals(AutomaticRecordingDecision.Start, detection.lastDecision)
        assertEquals(SessionState.Running, capture.state.value)
        assertEquals(MomentState.CandidateInMemory(QualityTier.Proxy), moments.state.value)
        assertEquals(0, moments.stopCalls)
    }

    @Test
    fun rescueNeverClaimsSavedBeforeMediaStoreSuccess() = runTest {
        val saveGate = CompletableDeferred<Unit>()
        val moments = FakeSessionMoments(saveGate)
        val viewModel = viewModel(moments = moments)

        viewModel.onAction(SessionAction.StartPreflight)
        viewModel.onPermissionResult(AppPermission.Camera, true)
        viewModel.onAction(SessionAction.ConnectGimbal)

        viewModel.onAction(SessionAction.Rescue)
        runCurrent()

        assertEquals("已锁定最近15秒，正在保存", viewModel.uiState.value.statusText)
        assertFalse(viewModel.uiState.value.showSaved)

        saveGate.complete(Unit)
        runCurrent()
        assertEquals("已保存到相册", viewModel.uiState.value.statusText)
        assertTrue(viewModel.uiState.value.showSaved)
    }

    @Test
    fun motionDisablesRescueUntilCoverageReturns() = runTest {
        val capture = FakeSessionCapture()
        val viewModel = viewModel(capture = capture)

        viewModel.onMotion(GimbalMotionState.Moving)
        runCurrent()

        assertEquals("云台调整中", viewModel.uiState.value.statusText)
        assertFalse(viewModel.uiState.value.rescueEnabled)
        assertEquals(SessionState.Paused(PauseReason.Motion), viewModel.uiState.value.sessionState)
    }

    @Test
    fun deniedCameraPermissionCanBeRetriedAfterAnExplicitUserAction() = runTest {
        val viewModel = viewModel()

        viewModel.onAction(SessionAction.StartPreflight)
        assertEquals(AppPermission.Camera, viewModel.uiState.value.permissionRequest)
        viewModel.onPermissionResult(AppPermission.Camera, granted = false)
        assertNull(viewModel.uiState.value.permissionRequest)

        viewModel.onAction(SessionAction.StartPreflight)
        assertEquals(AppPermission.Camera, viewModel.uiState.value.permissionRequest)
    }

    @Test
    fun externalCameraRevocationStopsAnActiveSessionAndRequiresResume() = runTest {
        val capture = FakeSessionCapture()
        val viewModel = viewModel(capture = capture)

        viewModel.onAction(SessionAction.StartPreflight)
        viewModel.onPermissionResult(AppPermission.Camera, granted = true)
        runCurrent()

        viewModel.onSystemPermissionChanged(AppPermission.Camera, granted = false)
        runCurrent()

        assertEquals(listOf(StopReason.PermissionLost), capture.stopReasons)
        assertTrue(viewModel.uiState.value.resumeConfirmationRequired)
    }

    @Test
    fun externalCameraGrantIsAcceptedWithoutRecreatingTheViewModel() = runTest {
        val coordinator = PermissionCoordinator()
        val viewModel = SessionViewModel(
            capture = FakeSessionCapture(),
            moments = FakeSessionMoments(),
            gimbal = FakeSessionGimbal(),
            permissionCoordinator = coordinator,
            clock = MonotonicClock { testScheduler.currentTime * 1_000 },
            scope = backgroundScope,
        )

        viewModel.onAction(SessionAction.StartPreflight)
        viewModel.onPermissionResult(AppPermission.Camera, granted = false)
        viewModel.onSystemPermissionChanged(AppPermission.Camera, granted = true)

        assertEquals(PermissionStatus.Granted, coordinator.status(AppPermission.Camera))
    }

    @Test
    fun appStopUsesSameStopPathAndRequiresExplicitResume() = runTest {
        val capture = FakeSessionCapture()
        val moments = FakeSessionMoments()
        val viewModel = viewModel(capture, moments)

        viewModel.onAppStopped()
        runCurrent()

        assertEquals(listOf(StopReason.Background), capture.stopReasons)
        assertEquals(1, moments.stopCalls)
        assertTrue(viewModel.uiState.value.resumeConfirmationRequired)
        assertFalse(viewModel.uiState.value.stopEnabled)
        assertEquals("会话已暂停，请确认后重新开始", viewModel.uiState.value.statusText)
    }

    @Test
    fun confirmedResumeRequestsCameraBindingAgain() = runTest {
        val viewModel = viewModel()
        viewModel.onAction(SessionAction.StartPreflight)
        viewModel.onPermissionResult(AppPermission.Camera, true)
        runCurrent()
        viewModel.onAppStopped()
        runCurrent()
        viewModel.onAction(SessionAction.ConfirmResume)
        assertEquals(AppPermission.Camera, viewModel.uiState.value.permissionRequest)
        viewModel.onPermissionResult(AppPermission.Camera, true)
        runCurrent()
        assertEquals(SessionState.Running, viewModel.uiState.value.sessionState)
        assertFalse(viewModel.uiState.value.resumeConfirmationRequired)
    }

    @Test
    fun realControllersRescueAfterStopAndConfirmedRestart() = runTest {
        var complete = false
        val capture = CaptureSessionController(
            sampler = object : CaptureSamplerPort {
                override val isRunning = true
                override suspend fun stop() = Unit
            },
            buffer = object : CaptureBufferPort {
                override suspend fun wipe() { complete = false }
                override suspend fun frameCount() = if (complete) 120 else 0
                override suspend fun hasCompleteCoverage(endingAtUs: Long, lookbackUs: Long) = complete
            },
            preview = CapturePreviewPort {}, gimbal = CaptureGimbalPort {},
            clock = MonotonicClock { 0 }, scope = backgroundScope,
            initialState = SessionState.Idle,
        )
        var saves = 0
        val moments = MomentCoordinator(
            source = object : MomentRescueSource {
                override suspend fun ownedMomentSnapshot(endingAtUs: Long, lookbackUs: Long) =
                    OwnedRescueAsset(listOf(RescueFrame(0, 1, 1, byteArrayOf(1))), 0, lookbackUs, complete, QualityTier.Proxy)
            },
            encoder = object : MomentEncoder {
                override suspend fun encode(asset: OwnedRescueAsset) =
                    EncodedMoment("staged.mp4", 15_000_000, 1, 1, 0, QualityTier.Proxy)
                override suspend fun discard(moment: EncodedMoment) = Unit
            },
            saver = MomentSaver { saves++; SavedMomentReference("content://media/$saves") },
            catalog = MomentCatalog {},
        )
        val viewModel = SessionViewModel(DomainSessionCapture(capture), DomainSessionMoments(moments),
            FakeSessionGimbal(), PermissionCoordinator(), MonotonicClock { 15_000_000 }, backgroundScope)
        viewModel.onAction(SessionAction.StartPreflight)
        viewModel.onPermissionResult(AppPermission.Camera, true)
        runCurrent()
        complete = true
        capture.onCoverageUpdated(15_000_000)
        viewModel.onAction(SessionAction.Rescue)
        runCurrent()
        assertEquals(1, saves)
        viewModel.onAppStopped()
        runCurrent()
        assertFalse(complete)
        viewModel.onAction(SessionAction.ConfirmResume)
        viewModel.onPermissionResult(AppPermission.Camera, true)
        runCurrent()
        assertEquals(SessionState.Running, capture.state.value)
        assertFalse(capture.rescueAvailable.value)
        complete = true
        capture.onCoverageUpdated(30_000_000)
        viewModel.onAction(SessionAction.Rescue)
        runCurrent()
        assertEquals(2, saves)
        assertTrue(viewModel.uiState.value.showSaved)
        viewModel.onAction(SessionAction.Stop)
        runCurrent()
        viewModel.onAction(SessionAction.StartPreflight)
        viewModel.onPermissionResult(AppPermission.Camera, true)
        runCurrent()
        complete = true
        capture.onCoverageUpdated(45_000_000)
        viewModel.onAction(SessionAction.Rescue)
        runCurrent()
        assertEquals(3, saves)
    }

    @Test
    fun noticesDoNotHideSaveFailure() = runTest {
        val moments = FakeSessionMoments(failSave = true)
        val viewModel = viewModel(moments = moments)
        viewModel.onAction(SessionAction.StartPreflight)
        viewModel.onPermissionResult(AppPermission.Camera, true)
        viewModel.onAction(SessionAction.ConnectGimbal)
        viewModel.onAction(SessionAction.Rescue)
        runCurrent()
        assertEquals("保存失败，可重试或放弃", viewModel.uiState.value.statusText)
        assertTrue(viewModel.uiState.value.showSaveFailure)
    }

    @Test
    fun catalogFailureRemainsVisibleAcrossStopAndRestartUntilCatalogOnlyRetry() = runTest {
        var publications = 0
        var inserts = 0
        var discards = 0
        val moments = MomentCoordinator(
            source = object : MomentRescueSource {
                override suspend fun ownedMomentSnapshot(endingAtUs: Long, lookbackUs: Long) =
                    OwnedRescueAsset(listOf(RescueFrame(0, 1, 1, byteArrayOf(1))), 0, lookbackUs, true, QualityTier.Proxy)
            },
            encoder = object : MomentEncoder {
                override suspend fun encode(asset: OwnedRescueAsset) =
                    EncodedMoment("staged.mp4", 15_000_000, 1, 1, 0, QualityTier.Proxy)
                override suspend fun discard(moment: EncodedMoment) { discards++ }
            },
            saver = MomentSaver { publications++; SavedMomentReference("content://media/$publications") },
            catalog = MomentCatalog { if (inserts++ == 0) error("catalog insert") },
        )
        val viewModel = SessionViewModel(FakeSessionCapture(), DomainSessionMoments(moments),
            FakeSessionGimbal(), PermissionCoordinator(), MonotonicClock { 15_000_000 }, backgroundScope)
        viewModel.onAction(SessionAction.Rescue)
        runCurrent()
        viewModel.onAction(SessionAction.ConnectGimbal)
        assertEquals("视频已在相册；本地记录失败，请重试记录，暂存副本仍保留", viewModel.uiState.value.statusText)
        assertTrue(viewModel.uiState.value.showCatalogFailure)
        assertFalse(viewModel.uiState.value.showSaved)
        assertFalse(viewModel.uiState.value.showSaveFailure)
        assertFalse(viewModel.uiState.value.rescueEnabled)
        viewModel.onAction(SessionAction.AbandonSave)
        runCurrent()
        assertEquals(0, discards)

        viewModel.onAppStopped()
        runCurrent()
        assertEquals("视频已在相册；本地记录失败，请重试记录，暂存副本仍保留", viewModel.uiState.value.statusText)
        viewModel.onAction(SessionAction.ConfirmResume)
        viewModel.onPermissionResult(AppPermission.Camera, true)
        runCurrent()
        assertFalse(viewModel.uiState.value.rescueEnabled)
        viewModel.onAction(SessionAction.RetrySave)
        runCurrent()
        assertEquals("已保存到相册", viewModel.uiState.value.statusText)
        assertTrue(viewModel.uiState.value.showSaved)
        assertFalse(viewModel.uiState.value.showCatalogFailure)
        assertEquals(1, publications)
        assertEquals(2, inserts)
        assertEquals(1, discards)
    }

    @Test
    fun savedCleanupFailureIsVisible() = runTest {
        val viewModel = viewModel(moments = FakeSessionMoments(failCleanup = true))
        viewModel.onAction(SessionAction.Rescue)
        runCurrent()
        assertEquals("已保存到相册；暂存清理失败，请重试清理或在 Today 删除", viewModel.uiState.value.statusText)
        assertTrue(viewModel.uiState.value.showCleanupFailure)
        viewModel.onAction(SessionAction.RetryCleanup)
        runCurrent()
        assertFalse(viewModel.uiState.value.showCleanupFailure)
        assertEquals("已保存到相册", viewModel.uiState.value.statusText)
    }

    private fun kotlinx.coroutines.test.TestScope.viewModel(
        capture: FakeSessionCapture = FakeSessionCapture(),
        moments: FakeSessionMoments = FakeSessionMoments(),
    ) = SessionViewModel(
        capture = capture,
        moments = moments,
        gimbal = FakeSessionGimbal(),
        permissionCoordinator = PermissionCoordinator(),
        clock = MonotonicClock { testScheduler.currentTime * 1_000 },
        scope = backgroundScope,
    )
}

private class FakeSessionCapture : SessionCaptureActions {
    override val state = MutableStateFlow<SessionState>(SessionState.Running)
    override val rescueAvailable = MutableStateFlow(true)
    override val acceptFrames = MutableStateFlow(true)
    val stopReasons = mutableListOf<StopReason>()

    override suspend fun beginSession() {
        state.value = SessionState.Running
        acceptFrames.value = true
    }

    override suspend fun onMotion(next: GimbalMotionState) {
        if (next != GimbalMotionState.Idle) {
            state.value = SessionState.Paused(PauseReason.Motion)
            rescueAvailable.value = false
            acceptFrames.value = false
        }
    }

    override suspend fun stop(reason: StopReason): CaptureStopResult {
        stopReasons += reason
        val stopped = SessionState.Paused(PauseReason.UserStop)
        state.value = stopped
        rescueAvailable.value = false
        acceptFrames.value = false
        return CaptureStopResult(reason, stopped, 1_000)
    }
}

private class FakeSessionMoments(
    private val saveGate: CompletableDeferred<Unit>? = null,
    private val failSave: Boolean = false,
    private val failCleanup: Boolean = false,
    private val beginGate: CompletableDeferred<Unit>? = null,
    private val rescueFailure: MomentFailure? = null,
) : SessionMomentActions {
    override val state = MutableStateFlow<MomentState>(MomentState.CandidateInMemory(QualityTier.Proxy))
    var stopCalls = 0
    var rescueCalls = 0
    override suspend fun beginSession() { beginGate?.await() }

    override suspend fun captureRescue(nowUs: Long): MomentResult {
        rescueCalls++
        rescueFailure?.let {
            state.value = MomentState.AssetMissing
            return MomentResult(state.value, it)
        }
        state.value = MomentState.Saving(QualityTier.Proxy)
        saveGate?.await()
        state.value = if (failSave) MomentState.SaveFailed else MomentState.Saved(QualityTier.Proxy)
        return MomentResult(state.value, if (failCleanup) MomentFailure.CleanupFailed else null)
    }

    override suspend fun retrySaving(): MomentResult = MomentResult(state.value, MomentFailure.NoPendingMoment)
    override suspend fun abandon(): MomentResult = MomentResult(MomentState.Deleted)
    override suspend fun retryCleanup(): MomentResult = MomentResult(state.value)
    override fun onStop() {
        stopCalls += 1
    }
}

private class FakeSessionGimbal : SessionGimbalStatus {
    override val connection = MutableStateFlow(GimbalConnectionState.Ready)
    override val motion = MutableStateFlow(GimbalMotionState.Idle)
    override val telemetry = MutableStateFlow(GimbalTelemetry(temperatureC = 31.5))
}
