package ai.argvid.gen0.session

import ai.argvid.gen0.domain.capture.*
import ai.argvid.gen0.domain.gimbal.GimbalConnectionState
import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.gimbal.GimbalTelemetry
import ai.argvid.gen0.domain.moment.*
import ai.argvid.gen0.domain.session.PauseReason
import ai.argvid.gen0.domain.session.SessionState
import ai.argvid.gen0.domain.time.MonotonicClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRestartBindingTest {
    @Test
    fun todayOrBackgroundStopTearsDownFreshBindingWhileRestartWaitsForOldMoment() = runTest {
        val harness = BindingHarness(this)
        val viewModel = harness.viewModel
        viewModel.onAction(SessionAction.StartPreflight)
        harness.bindCamera()
        viewModel.onPermissionResult(AppPermission.Camera, true)
        runCurrent()
        harness.capture.onCoverageUpdated(15_000_000)
        viewModel.onAction(SessionAction.Rescue)
        runCurrent()
        assertTrue(harness.moments.state.value is MomentState.Encoding)

        viewModel.onAppStopped()
        runCurrent()
        assertFalse(harness.sampler.isRunning)
        assertEquals(listOf(1), harness.sampler.stoppedBindings)

        viewModel.onAction(SessionAction.ConfirmResume)
        harness.bindCamera()
        viewModel.onPermissionResult(AppPermission.Camera, true)
        runCurrent()
        assertTrue(harness.sampler.isRunning)
        assertFalse(harness.capture.acceptFrames.value)
        assertTrue(harness.moments.state.value is MomentState.Encoding)

        // Both the Today tab and Activity ON_STOP use this same delivered action.
        viewModel.onAppStopped()
        runCurrent()
        assertFalse(harness.sampler.isRunning)
        assertEquals(listOf(1, 2), harness.sampler.stoppedBindings)
        harness.oldEncode.complete(Unit)
        runCurrent()
        assertFalse(harness.sampler.isRunning)
        assertFalse(harness.capture.acceptFrames.value)
        assertEquals(SessionState.Paused(PauseReason.UserStop), harness.capture.state.value)
        assertEquals(2, harness.sampler.bindings)
        assertEquals(0, harness.saved)
    }

    @Test
    fun cancellationDuringHostBindingDiscardsItsPartialBinding() = runTest {
        val harness = BindingHarness(this)
        harness.bindCamera()
        harness.capture.stop(StopReason.Background)
        val bindingGate = CompletableDeferred<Unit>()
        val binding = launch {
            harness.capture.bindCamera {
                harness.sampler.bind()
                bindingGate.await()
            }
        }
        runCurrent()
        assertTrue(harness.sampler.isRunning)
        binding.cancelAndJoin()
        assertFalse(harness.sampler.isRunning)
        harness.capture.stop(StopReason.Background)
        assertEquals(listOf(1, 2), harness.sampler.stoppedBindings)
    }
}

private class BindingHarness(scope: TestScope) {
    val sampler = StatefulSampler()
    val oldEncode = CompletableDeferred<Unit>()
    var saved = 0
    val capture = CaptureSessionController(
        sampler = sampler,
        buffer = object : CaptureBufferPort {
            override suspend fun wipe() = Unit
            override suspend fun frameCount() = 120
            override suspend fun hasCompleteCoverage(endingAtUs: Long, lookbackUs: Long) = true
        },
        preview = CapturePreviewPort {}, gimbal = CaptureGimbalPort {},
        clock = MonotonicClock { 0 }, scope = scope.backgroundScope,
        initialState = SessionState.Idle,
    )
    val moments = MomentCoordinator(
        source = object : MomentRescueSource {
            override suspend fun ownedMomentSnapshot(endingAtUs: Long, lookbackUs: Long) =
                OwnedRescueAsset(emptyList(), 0, lookbackUs, true, QualityTier.Proxy)
        },
        encoder = object : MomentEncoder {
            override suspend fun encode(asset: OwnedRescueAsset): EncodedMoment {
                oldEncode.await()
                return EncodedMoment("synthetic.mp4", 15_000_000, 960, 540, 0, QualityTier.Proxy)
            }
            override suspend fun discard(moment: EncodedMoment) = Unit
        },
        saver = MomentSaver { saved++; SavedMomentReference("content://media/synthetic") },
        catalog = MomentCatalog {},
    )
    val viewModel = SessionViewModel(
        DomainSessionCapture(capture), DomainSessionMoments(moments),
        object : SessionGimbalStatus {
            override val connection = MutableStateFlow(GimbalConnectionState.Disconnected)
            override val motion = MutableStateFlow(GimbalMotionState.Idle)
            override val telemetry = MutableStateFlow(GimbalTelemetry())
        },
        PermissionCoordinator(), MonotonicClock { 15_000_000 }, scope.backgroundScope,
    )

    suspend fun bindCamera() = capture.bindCamera {
        sampler.stop()
        sampler.bind()
    }
}

private class StatefulSampler : CaptureSamplerPort {
    override var isRunning = false
    var bindings = 0
    val stoppedBindings = mutableListOf<Int>()

    fun bind() {
        check(!isRunning)
        bindings++
        isRunning = true
    }

    override suspend fun stop() {
        if (isRunning) stoppedBindings += bindings
        isRunning = false
    }
}
