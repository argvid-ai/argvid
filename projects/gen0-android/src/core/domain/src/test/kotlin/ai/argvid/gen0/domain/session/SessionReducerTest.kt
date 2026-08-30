package ai.argvid.gen0.domain.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionReducerTest {
    private val reducer = SessionReducer()

    @Test
    fun motionPausesRunningSession() {
        assertEquals(
            TransitionResult.Accepted(SessionState.Paused(PauseReason.Motion)),
            reducer.reduce(SessionState.Running, SessionEvent.MotionStarted),
        )
    }

    @Test
    fun motionDoesNotResumeBeforeBufferIsWarm() {
        assertEquals(
            TransitionResult.Rejected(TransitionError.BufferNotWarm),
            reducer.reduce(
                SessionState.Paused(PauseReason.Motion),
                SessionEvent.ResumeRequested,
            ),
        )
    }

    @Test
    fun settlingKeepsMotionPauseUntilBufferWarmupCompletes() {
        val paused = SessionState.Paused(PauseReason.Motion)

        assertEquals(
            TransitionResult.Accepted(paused),
            reducer.reduce(paused, SessionEvent.MotionSettled),
        )
        assertEquals(
            TransitionResult.Accepted(SessionState.Running),
            reducer.reduce(paused, SessionEvent.RescueBufferWarmed),
        )
    }

    @Test
    fun startupAndEndingFollowTheOnlyLegalPath() {
        assertEquals(
            TransitionResult.Accepted(SessionState.Preflight),
            reducer.reduce(SessionState.Idle, SessionEvent.BeginPreflight),
        )
        assertEquals(
            TransitionResult.Accepted(SessionState.Calibrating),
            reducer.reduce(SessionState.Preflight, SessionEvent.PreflightPassed),
        )
        assertEquals(
            TransitionResult.Accepted(SessionState.Running),
            reducer.reduce(SessionState.Calibrating, SessionEvent.CalibrationPassed),
        )
        assertEquals(
            TransitionResult.Accepted(SessionState.Ending),
            reducer.reduce(SessionState.Running, SessionEvent.StopRequested),
        )
        assertEquals(
            TransitionResult.Accepted(SessionState.Ended),
            reducer.reduce(SessionState.Ending, SessionEvent.EndCompleted),
        )
    }

    @Test
    fun unlistedTransitionsAreRejected() {
        assertEquals(
            TransitionResult.Rejected(TransitionError.IllegalTransition),
            reducer.reduce(SessionState.Idle, SessionEvent.MotionStarted),
        )
    }
}
