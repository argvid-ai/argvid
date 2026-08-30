package ai.argvid.gen0.session

import ai.argvid.gen0.domain.capture.CaptureStopResult
import ai.argvid.gen0.domain.capture.CaptureBufferPort
import ai.argvid.gen0.domain.capture.CaptureGimbalPort
import ai.argvid.gen0.domain.capture.CapturePreviewPort
import ai.argvid.gen0.domain.capture.CaptureSamplerPort
import ai.argvid.gen0.domain.capture.CaptureSessionController
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
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
        assertEquals("仅提供语义模拟器；不连接物理云台", viewModel.uiState.value.statusText)
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
    fun deniedCameraPermissionIsNotRequestedInALoop() = runTest {
        val viewModel = viewModel()

        viewModel.onAction(SessionAction.StartPreflight)
        assertEquals(AppPermission.Camera, viewModel.uiState.value.permissionRequest)
        viewModel.onPermissionResult(AppPermission.Camera, granted = false)
        assertNull(viewModel.uiState.value.permissionRequest)

        viewModel.onAction(SessionAction.StartPreflight)
        assertNull(viewModel.uiState.value.permissionRequest)
        assertEquals("相机权限未授予，采集不可用", viewModel.uiState.value.statusText)
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
) : SessionMomentActions {
    override val state = MutableStateFlow<MomentState>(MomentState.CandidateInMemory(QualityTier.Proxy))
    var stopCalls = 0
    override suspend fun beginSession() { beginGate?.await() }

    override suspend fun captureRescue(nowUs: Long): MomentResult {
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
