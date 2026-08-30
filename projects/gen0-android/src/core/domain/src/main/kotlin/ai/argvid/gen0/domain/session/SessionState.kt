package ai.argvid.gen0.domain.session

sealed interface SessionState {
    data object Idle : SessionState
    data object Preflight : SessionState
    data object Calibrating : SessionState
    data object Running : SessionState
    data class Paused(val reason: PauseReason) : SessionState
    data class Degraded(val reason: DegradedReason) : SessionState
    data object Ending : SessionState
    data object Ended : SessionState
}

enum class PauseReason {
    Privacy,
    Interruption,
    Motion,
    UserStop,
}

enum class DegradedReason {
    Thermal,
    Offline,
    Quota,
}
