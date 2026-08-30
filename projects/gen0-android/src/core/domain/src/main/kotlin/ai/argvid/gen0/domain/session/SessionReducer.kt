package ai.argvid.gen0.domain.session

class SessionReducer {
    fun reduce(
        current: SessionState,
        event: SessionEvent,
    ): TransitionResult = when {
        current == SessionState.Idle && event == SessionEvent.BeginPreflight ->
            accepted(SessionState.Preflight)

        current == SessionState.Preflight && event == SessionEvent.PreflightPassed ->
            accepted(SessionState.Calibrating)

        current == SessionState.Calibrating && event == SessionEvent.CalibrationPassed ->
            accepted(SessionState.Running)

        current == SessionState.Running && event == SessionEvent.MotionStarted ->
            accepted(SessionState.Paused(PauseReason.Motion))

        current is SessionState.Paused &&
            current.reason == PauseReason.Motion &&
            event == SessionEvent.MotionSettled -> accepted(current)

        current is SessionState.Paused &&
            current.reason == PauseReason.Motion &&
            event == SessionEvent.RescueBufferWarmed -> accepted(SessionState.Running)

        current is SessionState.Paused &&
            current.reason == PauseReason.Motion &&
            event == SessionEvent.ResumeRequested -> rejected(TransitionError.BufferNotWarm)

        current == SessionState.Running && event is SessionEvent.Interrupted ->
            accepted(SessionState.Paused(PauseReason.Interruption))

        current.isActive() && event == SessionEvent.StopRequested ->
            accepted(SessionState.Ending)

        current == SessionState.Ending && event == SessionEvent.EndCompleted ->
            accepted(SessionState.Ended)

        else -> rejected(TransitionError.IllegalTransition)
    }

    private fun accepted(state: SessionState) = TransitionResult.Accepted(state)

    private fun rejected(error: TransitionError) = TransitionResult.Rejected(error)

    private fun SessionState.isActive(): Boolean = when (this) {
        SessionState.Idle,
        SessionState.Ending,
        SessionState.Ended,
        -> false

        SessionState.Preflight,
        SessionState.Calibrating,
        SessionState.Running,
        is SessionState.Paused,
        is SessionState.Degraded,
        -> true
    }
}

sealed interface TransitionResult {
    data class Accepted(val state: SessionState) : TransitionResult
    data class Rejected(val error: TransitionError) : TransitionResult
}

enum class TransitionError {
    BufferNotWarm,
    IllegalTransition,
}
