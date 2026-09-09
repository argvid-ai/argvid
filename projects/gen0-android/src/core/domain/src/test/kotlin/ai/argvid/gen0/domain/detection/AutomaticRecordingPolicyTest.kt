package ai.argvid.gen0.domain.detection

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomaticRecordingPolicyTest {
    private val start = Instant.parse("2026-09-03T00:00:00Z")

    @Test
    fun startsOnlyAfterTwoSecondsOfContinuousPresence() {
        val policy = AutomaticRecordingPolicy()

        assertNull(policy.update(subjectPresent = true, now = start))
        assertNull(policy.update(subjectPresent = true, now = start.plusMillis(1_999)))
        assertEquals(
            AutomaticRecordingDecision.Start,
            policy.update(subjectPresent = true, now = start.plusSeconds(2)),
        )
    }

    @Test
    fun stopsAfterThreeSecondsOfContinuousAbsenceAndStartsCooldown() {
        val policy = AutomaticRecordingPolicy()
        policy.update(subjectPresent = true, now = start)
        policy.update(subjectPresent = true, now = start.plusSeconds(2))

        assertNull(policy.update(subjectPresent = false, now = start.plusSeconds(2)))
        assertNull(policy.update(subjectPresent = false, now = start.plusMillis(4_999)))
        assertEquals(
            AutomaticRecordingDecision.Stop,
            policy.update(subjectPresent = false, now = start.plusSeconds(5)),
        )
        assertNull(policy.update(subjectPresent = true, now = start.plusSeconds(19)))
        assertNull(policy.update(subjectPresent = true, now = start.plusSeconds(20)))
        assertEquals(
            AutomaticRecordingDecision.Start,
            policy.update(subjectPresent = true, now = start.plusSeconds(22)),
        )
    }

    @Test
    fun intermittentPresenceDoesNotAccumulateBeforeStart() {
        val policy = AutomaticRecordingPolicy()

        policy.update(subjectPresent = true, now = start)
        policy.update(subjectPresent = false, now = start.plusSeconds(1))
        assertNull(policy.update(subjectPresent = true, now = start.plusSeconds(2)))
        assertEquals(
            AutomaticRecordingDecision.Start,
            policy.update(subjectPresent = true, now = start.plusSeconds(4)),
        )
    }
}
