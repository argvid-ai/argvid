package ai.argvid.gen0.domain.session

sealed interface SessionEvent {
    data object BeginPreflight : SessionEvent
    data object PreflightPassed : SessionEvent
    data object CalibrationPassed : SessionEvent
    data object MotionStarted : SessionEvent
    data object MotionSettled : SessionEvent
    data object RescueBufferWarmed : SessionEvent
    data object ResumeRequested : SessionEvent
    data class Interrupted(val reason: String) : SessionEvent
    data object StopRequested : SessionEvent
    data object EndCompleted : SessionEvent
}
