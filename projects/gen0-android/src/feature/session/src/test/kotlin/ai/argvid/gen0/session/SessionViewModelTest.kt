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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    fun selectingRealGimbalRequestsBluetoothPermissionWithoutStartingMotion() = runTest {
        val model = viewModel()

        model.onAction(SessionAction.SelectGimbalSource(GimbalSource.RealBle))
        runCurrent()

        assertEquals(GimbalSource.RealBle, model.uiState.value.gimbal.source)
        assertEquals(AppPermission.Bluetooth, model.uiState.value.permissionRequest)
        assertTrue(model.uiState.value.gimbalNotice.orEmpty().contains("不发送电机命令"))
        assertEquals(GimbalMotionState.Idle, model.uiState.value.gimbal.motion)

        model.onPermissionResult(AppPermission.Bluetooth, granted = true)
        runCurrent()

        assertEquals(GimbalSource.RealBle, model.uiState.value.gimbal.source)
        assertEquals(null, model.uiState.value.permissionRequest)
        assertTrue(model.uiState.value.gimbalNotice.orEmpty().contains("不发送电机命令"))
    }

    @Test
    fun deniedBluetoothPermissionKeepsSimulatorAvailableAndDoesNotMoveGimbal() = runTest {
        val model = viewModel()

        model.onAction(SessionAction.SelectGimbalSource(GimbalSource.RealBle))
        runCurrent()
        model.onPermissionResult(AppPermission.Bluetooth, granted = false)
        runCurrent()

        assertEquals(GimbalSource.RealBle, model.uiState.value.gimbal.source)
        assertEquals(GimbalMotionState.Idle, model.uiState.value.gimbal.motion)
        assertTrue(model.uiState.value.statusText.contains("蓝牙权限未授予"))

        model.onAction(SessionAction.ConnectGimbal)
        runCurrent()
        assertEquals(GimbalSource.Simulator, model.uiState.value.gimbal.source)
        assertEquals("仅提供语义模拟器；不连接物理云台", model.uiState.value.gimbalNotice)
    }

    @Test
    fun realGimbalScanIsReadOnlyAndReportsCandidateNames() = runTest {
        val model = SessionViewModel(
            capture = FakeSessionCapture(),
            moments = FakeSessionMoments(),
            gimbal = FakeSessionGimbal(),
            permissionCoordinator = PermissionCoordinator(),
            clock = MonotonicClock { testScheduler.currentTime * 1_000 },
            scope = backgroundScope,
            discovery = GimbalDiscovery { listOf("F32C-Gimbal") },
        )

        model.onAction(SessionAction.SelectGimbalSource(GimbalSource.RealBle))
        runCurrent()
        model.onPermissionResult(AppPermission.Bluetooth, granted = true)
        model.onAction(SessionAction.ScanRealGimbal)
        runCurrent()

        assertEquals(listOf("F32C-Gimbal"), model.uiState.value.gimbalDiscovery.candidates)
        assertEquals(false, model.uiState.value.gimbalDiscovery.scanning)
        assertEquals(GimbalMotionState.Idle, model.uiState.value.gimbal.motion)
        assertTrue(model.uiState.value.gimbalNotice.orEmpty().contains("未连接"))
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

    @Test
    fun trackingToggleStartsDetectionAndNudgesBoundedCorrections() = runTest {
        val source = FakeDetectionSource()
        val nudges = mutableListOf<Pair<Double, Double>>()
        var stops = 0
        val viewModel = SessionViewModel(
            capture = FakeSessionCapture(),
            moments = FakeSessionMoments(),
            gimbal = FakeSessionGimbal(),
            permissionCoordinator = PermissionCoordinator(),
            clock = MonotonicClock { testScheduler.currentTime * 1_000 },
            scope = backgroundScope,
            detection = source,
            trackingDriver = object : GimbalTrackingDriver {
                override suspend fun nudge(panDeltaDeg: Double, tiltDeltaDeg: Double): Boolean {
                    nudges += panDeltaDeg to tiltDeltaDeg
                    return true
                }

                override suspend fun stop(): Boolean {
                    stops += 1
                    return true
                }
            },
        )
        viewModel.onAction(SessionAction.SetTrackingEnabled(true))
        runCurrent()
        assertEquals(1, source.started)
        assertTrue(viewModel.uiState.value.subjectDetection.trackingEnabled)
        // Fixed face confidence 0.5; default face sensitivity (progress 50) maps to ~0.14 width ratio.
        assertEquals(0.5f, source.lastThresholds!!.first, 1e-6f)
        assertEquals(0.11f, source.lastThresholds!!.second, 1e-4f)

        val at = Instant.parse("2026-09-14T10:00:00Z")
        source.bus.emit(SubjectObservation(setOf(SubjectLabel.FACE), at, centerX = 1.0f, centerY = 0.5f))
        runCurrent()
        // Right-edge error (30°) smooths to 15° and clamps to the 8° tracker bound;
        // the driver issues one bounded absolute move from fresh telemetry.
        assertEquals(listOf(8.0 to 0.0), nudges)
        assertTrue(viewModel.uiState.value.subjectDetection.detectorAvailable)

        // Absent sightings carry no geometry: no further commands are issued.
        source.bus.emit(SubjectObservation(emptySet(), at.plusMillis(50)))
        runCurrent()
        source.bus.emit(SubjectObservation(emptySet(), at.plusMillis(600)))
        runCurrent()
        assertEquals(listOf(8.0 to 0.0), nudges)

        viewModel.onAction(SessionAction.SetTrackingEnabled(false))
        runCurrent()
        assertEquals(2, stops)
    }

    @Test
    fun disablingTrackingStopsDetectionAndClearsState() = runTest {
        val source = FakeDetectionSource()
        val viewModel = SessionViewModel(
            capture = FakeSessionCapture(),
            moments = FakeSessionMoments(),
            gimbal = FakeSessionGimbal(),
            permissionCoordinator = PermissionCoordinator(),
            clock = MonotonicClock { testScheduler.currentTime * 1_000 },
            scope = backgroundScope,
            detection = source,
        )
        viewModel.onAction(SessionAction.SetTrackingEnabled(true))
        runCurrent()
        viewModel.onAction(SessionAction.SetTrackingEnabled(false))
        runCurrent()
        assertEquals(1, source.stopped)
        assertFalse(viewModel.uiState.value.subjectDetection.trackingEnabled)
        assertFalse(viewModel.uiState.value.subjectDetection.detectorAvailable)
    }

    @Test
    fun selectingTheSimulatorSourceDisablesTracking() = runTest {
        val source = FakeDetectionSource()
        val viewModel = SessionViewModel(
            capture = FakeSessionCapture(),
            moments = FakeSessionMoments(),
            gimbal = FakeSessionGimbal(),
            permissionCoordinator = PermissionCoordinator(),
            clock = MonotonicClock { testScheduler.currentTime * 1_000 },
            scope = backgroundScope,
            detection = source,
        )
        viewModel.onAction(SessionAction.SetTrackingEnabled(true))
        runCurrent()
        viewModel.onAction(SessionAction.SelectGimbalSource(GimbalSource.Simulator))
        runCurrent()
        assertEquals(1, source.stopped)
        assertFalse(viewModel.uiState.value.subjectDetection.trackingEnabled)
    }

    @Test
    fun sensitivityChangesPropagateToTheRunningDetectionSource() = runTest {
        val source = FakeDetectionSource()
        val viewModel = SessionViewModel(
            capture = FakeSessionCapture(),
            moments = FakeSessionMoments(),
            gimbal = FakeSessionGimbal(),
            permissionCoordinator = PermissionCoordinator(),
            clock = MonotonicClock { testScheduler.currentTime * 1_000 },
            scope = backgroundScope,
            detection = source,
        )
        viewModel.onAction(SessionAction.SetTrackingEnabled(true))
        runCurrent()
        viewModel.onFaceDetectionSensitivityChanged(0)
        runCurrent()
        assertEquals(0.5f, source.lastThresholds!!.first, 1e-6f)
        assertEquals(0.18f, source.lastThresholds!!.second, 1e-4f)
    }

    @Test
    fun measuredMotionDropsConflatedCorrections() = runTest {
        val source = FakeDetectionSource()
        val nudges = mutableListOf<Pair<Double, Double>>()
        val gimbal = FakeSessionGimbal()
        val viewModel = SessionViewModel(
            capture = FakeSessionCapture(),
            moments = FakeSessionMoments(),
            gimbal = gimbal,
            permissionCoordinator = PermissionCoordinator(),
            clock = MonotonicClock { testScheduler.currentTime * 1_000 },
            scope = backgroundScope,
            detection = source,
            trackingDriver = object : GimbalTrackingDriver {
                override suspend fun nudge(panDeltaDeg: Double, tiltDeltaDeg: Double): Boolean {
                    nudges += panDeltaDeg to tiltDeltaDeg
                    return true
                }

                override suspend fun stop(): Boolean = true
            },
        )
        viewModel.onAction(SessionAction.SetTrackingEnabled(true))
        runCurrent()
        val at = Instant.parse("2026-09-15T10:00:00Z")

        // Static telemetry: the first bounded correction issues.
        source.bus.emit(SubjectObservation(setOf(SubjectLabel.FACE), at, centerX = 1.0f, centerY = 0.5f))
        runCurrent()
        assertEquals(1, nudges.size)

        // Measured motion (2° in 200 ms = 10°/s): the conflated correction from the
        // next sighting is dropped until the axes settle.
        gimbal.telemetry.value = gimbal.telemetry.value.copy(panDeg = 2.0, measuredAtMs = 200)
        runCurrent()
        source.bus.emit(SubjectObservation(setOf(SubjectLabel.FACE), at.plusMillis(400), centerX = 1.0f, centerY = 0.5f))
        runCurrent()
        assertEquals("correction during motion must be dropped", 1, nudges.size)

        // Motion settles (same angle in the next sample): corrections resume.
        gimbal.telemetry.value = gimbal.telemetry.value.copy(measuredAtMs = 400)
        runCurrent()
        source.bus.emit(SubjectObservation(setOf(SubjectLabel.FACE), at.plusMillis(800), centerX = 1.0f, centerY = 0.5f))
        runCurrent()
        assertEquals("settled correction must issue", 2, nudges.size)
    }

    // ---- A4: unified real-control teardown ----

    @Test
    fun stopActionTearsDownTrackingAndHoldsRealGimbal() = runTest {
        val source = FakeDetectionSource()
        var holds = 0
        val viewModel = SessionViewModel(
            capture = FakeSessionCapture(),
            moments = FakeSessionMoments(),
            gimbal = FakeSessionGimbal(),
            permissionCoordinator = PermissionCoordinator(),
            clock = MonotonicClock { testScheduler.currentTime * 1_000 },
            scope = backgroundScope,
            detection = source,
            realGimbalTeardown = { holds++; true },
        )
        viewModel.onAction(SessionAction.SetTrackingEnabled(true))
        runCurrent()
        assertTrue(viewModel.uiState.value.subjectDetection.trackingEnabled)
        viewModel.onAction(SessionAction.Stop)
        runCurrent()
        assertFalse("STOP must disable tracking", viewModel.uiState.value.subjectDetection.trackingEnabled)
        assertEquals("STOP must stop the detection source", 1, source.stopped)
        assertEquals("STOP must hold the real gimbal", 1, holds)
    }

    @Test
    fun appStoppedTearsDownEvenWhenCaptureIsIdle() = runTest {
        val source = FakeDetectionSource()
        var holds = 0
        val capture = FakeSessionCapture()
        capture.state.value = SessionState.Idle
        val viewModel = SessionViewModel(
            capture = capture,
            moments = FakeSessionMoments(),
            gimbal = FakeSessionGimbal(),
            permissionCoordinator = PermissionCoordinator(),
            clock = MonotonicClock { testScheduler.currentTime * 1_000 },
            scope = backgroundScope,
            detection = source,
            realGimbalTeardown = { holds++; true },
        )
        viewModel.onAction(SessionAction.SetTrackingEnabled(true))
        runCurrent()
        viewModel.onAppStopped()
        runCurrent()
        // Capture was Idle — the old code would have early-returned and skipped
        // the real-control teardown entirely.
        assertFalse("onAppStopped must disable tracking even with idle capture",
            viewModel.uiState.value.subjectDetection.trackingEnabled)
        assertEquals(1, source.stopped)
        assertEquals(1, holds)
    }

    @Test
    fun reEnablingTrackingAfterTeardownRequiresNewOptIn() = runTest {
        val source = FakeDetectionSource()
        val viewModel = SessionViewModel(
            capture = FakeSessionCapture(),
            moments = FakeSessionMoments(),
            gimbal = FakeSessionGimbal(),
            permissionCoordinator = PermissionCoordinator(),
            clock = MonotonicClock { testScheduler.currentTime * 1_000 },
            scope = backgroundScope,
            detection = source,
        )
        viewModel.onAction(SessionAction.SetTrackingEnabled(true))
        runCurrent()
        viewModel.onAppStopped()
        runCurrent()
        assertFalse(viewModel.uiState.value.subjectDetection.trackingEnabled)
        assertEquals(1, source.stopped)
        // Tracking stays off after app stop; nothing auto-resumes it.
        runCurrent()
        testScheduler.advanceTimeBy(5_000)
        runCurrent()
        assertFalse(viewModel.uiState.value.subjectDetection.trackingEnabled)
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

private class IdleCaptureActions : SessionCaptureActions {
    override val state = MutableStateFlow(SessionState.Idle)
    override val rescueAvailable = MutableStateFlow(false)
    override val acceptFrames = MutableStateFlow(false)
    override suspend fun beginSession() = Unit
    override suspend fun onMotion(next: GimbalMotionState) = Unit
    override suspend fun stop(reason: StopReason) = CaptureStopResult(reason, SessionState.Idle, 0)
}

private class IdleMomentActions : SessionMomentActions {
    override val state = MutableStateFlow<MomentState>(MomentState.SaveFailed)
    override suspend fun beginSession() = Unit
    override suspend fun captureRescue(nowUs: Long) = MomentResult(MomentState.SaveFailed)
    override suspend fun retrySaving() = MomentResult(MomentState.SaveFailed)
    override suspend fun abandon() = MomentResult(MomentState.SaveFailed)
    override suspend fun retryCleanup() = MomentResult(MomentState.SaveFailed)
    override fun onStop() = Unit
}

private class FakeDetectionSource : FaceDetectionSource {
    val bus = MutableSharedFlow<SubjectObservation>(extraBufferCapacity = 16)
    override val observations: Flow<SubjectObservation> = bus
    var started = 0
    var stopped = 0
    var lastThresholds: Pair<Float, Float>? = null

    override fun start(minConfidence: Float, minWidthRatio: Float) {
        started += 1
        lastThresholds = minConfidence to minWidthRatio
    }

    override fun updateThresholds(minConfidence: Float, minWidthRatio: Float) {
        lastThresholds = minConfidence to minWidthRatio
    }

    override fun stop() {
        stopped += 1
    }
}

private class FakeSessionGimbal : SessionGimbalStatus {
    override val connection = MutableStateFlow(GimbalConnectionState.Ready)
    override val motion = MutableStateFlow(GimbalMotionState.Idle)
    override val telemetry = MutableStateFlow(GimbalTelemetry(temperatureC = 31.5))
}
