package ai.argvid.gen0.domain.capture

import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.session.PauseReason
import ai.argvid.gen0.domain.session.SessionEvent
import ai.argvid.gen0.domain.session.SessionReducer
import ai.argvid.gen0.domain.session.SessionState
import ai.argvid.gen0.domain.session.TransitionResult
import ai.argvid.gen0.domain.time.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class StopReason {
    Screen,
    Background,
    Interruption,
    PermissionLost,
    SessionEnded,
}

data class CaptureStopResult(
    val reason: StopReason,
    val state: SessionState,
    val durationUs: Long,
)

class CaptureSessionController(
    private val sampler: CaptureSamplerPort,
    private val buffer: CaptureBufferPort,
    private val preview: CapturePreviewPort,
    private val gimbal: CaptureGimbalPort,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
    private val diagnostics: CaptureDiagnostics = CaptureDiagnostics(),
    private val reducer: SessionReducer = SessionReducer(),
    initialState: SessionState = SessionState.Running,
    private val settlingUs: Long = 300_000,
    private val rescueLookbackUs: Long = 15_000_000,
) {
    private val transitionMutex = Mutex()
    private val mutableState = MutableStateFlow(initialState)
    private val mutableAcceptFrames = MutableStateFlow(initialState == SessionState.Running)
    private val mutableRescueAvailable = MutableStateFlow(false)
    private var settlingJob: Job? = null
    private var stopping = false
    private var completedStop: CaptureStopResult? = null

    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    val acceptFrames: StateFlow<Boolean> = mutableAcceptFrames.asStateFlow()
    val rescueAvailable: StateFlow<Boolean> = mutableRescueAvailable.asStateFlow()

    suspend fun bindCamera(binding: suspend () -> Unit) = transitionMutex.withLock {
        // A new host binding owns a new stop boundary, even before moment work lets
        // beginSession enable frames. An earlier receipt cannot cover this binding.
        completedStop = null
        stopping = false
        mutableState.value = SessionState.Idle
        mutableAcceptFrames.value = false
        mutableRescueAvailable.value = false
        try {
            binding()
        } catch (error: Exception) {
            withContext(NonCancellable) { sampler.stop() }
            throw error
        }
    }

    suspend fun beginSession() = transitionMutex.withLock {
        // Only an explicit start may reset the completed user-stop boundary.
        if (completedStop != null && mutableState.value == SessionState.Paused(PauseReason.UserStop)) {
            completedStop = null
            stopping = false
            mutableState.value = SessionState.Idle
        }
        if (stopping || mutableState.value != SessionState.Idle) return@withLock
        listOf(
            SessionEvent.BeginPreflight,
            SessionEvent.PreflightPassed,
            SessionEvent.CalibrationPassed,
        ).forEach { event ->
            val transition = reducer.reduce(mutableState.value, event)
            check(transition is TransitionResult.Accepted)
            mutableState.value = transition.state
        }
        mutableAcceptFrames.value = true
        mutableRescueAvailable.value = false
    }

    suspend fun onMotion(next: GimbalMotionState) = transitionMutex.withLock {
        if (stopping || mutableState.value == SessionState.Idle) return@withLock
        when (next) {
            GimbalMotionState.Idle -> beginSettlingThenWarmup()
            GimbalMotionState.Moving,
            GimbalMotionState.Settling,
            GimbalMotionState.Holding,
            GimbalMotionState.Stalled,
            GimbalMotionState.Fault,
            -> pauseAndWipe()
        }
    }

    suspend fun onCoverageUpdated(nowUs: Long) = transitionMutex.withLock {
        if (stopping || !mutableAcceptFrames.value) return@withLock
        val complete = buffer.hasCompleteCoverage(nowUs, rescueLookbackUs)
        if (mutableState.value == SessionState.Running) {
            mutableRescueAvailable.value = complete
            return@withLock
        }
        if (mutableState.value != SessionState.Paused(PauseReason.Motion) || !complete) return@withLock

        val transition = reducer.reduce(mutableState.value, SessionEvent.RescueBufferWarmed)
        if (transition is TransitionResult.Accepted) {
            mutableState.value = transition.state
            mutableRescueAvailable.value = true
        }
    }

    suspend fun stop(reason: StopReason): CaptureStopResult = transitionMutex.withLock {
        completedStop?.let { return@withLock it }

        stopping = true
        settlingJob?.cancel()
        settlingJob = null
        mutableAcceptFrames.value = false
        mutableRescueAvailable.value = false
        val transition = reducer.reduce(mutableState.value, SessionEvent.StopRequested)
        if (transition is TransitionResult.Accepted) mutableState.value = transition.state

        val startedAtUs = clock.nowUs()
        sampler.stop()
        buffer.wipe()
        preview.hide()
        gimbal.requestHold()
        val durationUs = (clock.nowUs() - startedAtUs).coerceAtLeast(0)
        diagnostics.recordStop(durationUs)

        val stoppedState = SessionState.Paused(PauseReason.UserStop)
        mutableState.value = stoppedState
        CaptureStopResult(reason, stoppedState, durationUs).also { completedStop = it }
    }

    private suspend fun pauseAndWipe() {
        settlingJob?.cancel()
        settlingJob = null
        mutableAcceptFrames.value = false
        mutableRescueAvailable.value = false
        buffer.wipe()
        val transition = reducer.reduce(mutableState.value, SessionEvent.MotionStarted)
        mutableState.value = when (transition) {
            is TransitionResult.Accepted -> transition.state
            is TransitionResult.Rejected -> SessionState.Paused(PauseReason.Motion)
        }
    }

    private fun beginSettlingThenWarmup() {
        if (mutableState.value != SessionState.Paused(PauseReason.Motion)) return
        settlingJob?.cancel()
        settlingJob = scope.launch {
            delay(settlingUs / 1_000)
            transitionMutex.withLock {
                if (!stopping && mutableState.value == SessionState.Paused(PauseReason.Motion)) {
                    mutableAcceptFrames.value = true
                }
            }
        }
    }
}
