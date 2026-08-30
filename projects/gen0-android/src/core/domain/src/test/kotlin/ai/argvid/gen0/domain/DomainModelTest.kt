package ai.argvid.gen0.domain

import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.moment.MomentState
import ai.argvid.gen0.domain.moment.QualityTier
import ai.argvid.gen0.domain.session.PauseReason
import ai.argvid.gen0.domain.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainModelTest {
    @Test
    fun qualityTierStorageLabelsAreStable() {
        assertEquals(
            listOf("proxy", "hybrid", "hires"),
            QualityTier.entries.map { it.wireName },
        )
    }

    @Test
    fun runningIsDistinctFromMotionPause() {
        assertNotEquals(
            SessionState.Running,
            SessionState.Paused(PauseReason.Motion),
        )
    }

    @Test
    fun statesWithoutFootageDoNotExposeAQualityTier() {
        val states = listOf(
            MomentState.SaveFailed,
            MomentState.AssetMissing,
            MomentState.Deleted,
        )

        states.forEach { assertNull(it.qualityTier) }
    }

    @Test
    fun gimbalMotionWireNamesAreStable() {
        assertEquals(
            listOf("idle", "moving", "settling", "holding", "stalled", "fault"),
            GimbalMotionState.entries.map { it.wireName },
        )
    }
}
