package ai.argvid.gen0

import ai.argvid.gen0.session.SessionViewModel
import ai.argvid.gen0.today.TodayViewModel
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.isActive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RuntimeRecreationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun recreationRetainsOneRuntimePlayerAndFeatureOwnerGraph() {
        compose.waitForIdle()
        val oldActivity = compose.activity
        val provider = ViewModelProvider(oldActivity)
        val owner = provider[AppRuntimeViewModel::class.java]
        val session = provider[SessionViewModel::class.java]
        val today = provider["today", TodayViewModel::class.java]
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        val current = ViewModelProvider(compose.activity)
        assertNotSame(oldActivity, compose.activity)
        assertSame(owner, current[AppRuntimeViewModel::class.java])
        assertSame(session, current[SessionViewModel::class.java])
        assertSame(today, current["today", TodayViewModel::class.java])
        assertTrue(owner.viewModelScope.isActive)
    }
}
