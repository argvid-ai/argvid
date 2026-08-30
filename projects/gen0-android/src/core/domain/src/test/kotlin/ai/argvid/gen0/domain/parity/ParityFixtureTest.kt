package ai.argvid.gen0.domain.parity

import ai.argvid.gen0.domain.session.PauseReason
import ai.argvid.gen0.domain.session.SessionEvent
import ai.argvid.gen0.domain.session.SessionReducer
import ai.argvid.gen0.domain.session.SessionState
import ai.argvid.gen0.domain.session.TransitionResult
import ai.argvid.gen0.testing.FixtureLoader
import org.junit.Assert.assertEquals
import org.junit.Test

class ParityFixtureTest {
    @Test
    fun everySessionFixtureProducesItsExpectedState() {
        FixtureLoader.sessionScenarios().forEach { scenario ->
            val result = SessionReducer().reduce(
                scenario.initialState.toDomainState(),
                scenario.event.toDomainEvent(),
            )

            assertEquals(scenario.id, scenario.expected.state, result.acceptedStateWireName())
        }
    }

    private fun String.toDomainState(): SessionState = when (this) {
        "running" -> SessionState.Running
        "paused_motion" -> SessionState.Paused(PauseReason.Motion)
        else -> error("Unsupported fixture state: $this")
    }

    private fun String.toDomainEvent(): SessionEvent = when (this) {
        "motion_started" -> SessionEvent.MotionStarted
        "stop_requested" -> SessionEvent.StopRequested
        else -> error("Unsupported fixture event: $this")
    }

    private fun TransitionResult.acceptedStateWireName(): String? = when (this) {
        is TransitionResult.Accepted -> when (state) {
            SessionState.Running -> "running"
            SessionState.Ending -> "ending"
            SessionState.Paused(PauseReason.Motion) -> "paused_motion"
            else -> error("Unsupported accepted state: $state")
        }

        is TransitionResult.Rejected -> null
    }
}
