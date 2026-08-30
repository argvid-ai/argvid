package ai.argvid.gen0.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FixtureLoaderTest {
    @Test
    fun canonicalSessionFixturesAreSortedById() {
        assertEquals(
            listOf(
                "session.motion-started-pauses",
                "session.stop-from-running-ends",
            ),
            FixtureLoader.sessionScenarios().map { it.id },
        )
    }

    @Test
    fun unknownVersionEnumAndTopLevelKeyAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FixtureLoader.parseSession(validJson().replace("gen0.parity/1.0", "gen0.parity/2.0"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FixtureLoader.parseSession(validJson().replace("motion_started", "teleport"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FixtureLoader.parseSession(validJson().replace("\n}", ",\n  \"extra\": true\n}"))
        }
    }

    @Test
    fun missingAndDuplicateIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FixtureLoader.parseSession(validJson().replace("session.valid", ""))
        }

        val scenario = FixtureLoader.parseSession(validJson())
        assertThrows(IllegalArgumentException::class.java) {
            FixtureLoader.sortedUnique(listOf(scenario, scenario))
        }
    }

    private fun validJson() = """
        {
          "scenario_version": "gen0.parity/1.0",
          "id": "session.valid",
          "initial_state": "running",
          "event": "motion_started",
          "expected": {"accepted": true, "state": "paused_motion"}
        }
    """.trimIndent()
}
