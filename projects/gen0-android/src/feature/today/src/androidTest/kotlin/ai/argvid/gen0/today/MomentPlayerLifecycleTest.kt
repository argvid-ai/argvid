package ai.argvid.gen0.today

import ai.argvid.gen0.media.catalog.TodayAssetResult
import ai.argvid.gen0.media.catalog.TodayMoment
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MomentPlayerLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backgroundThenPlayReattachesNewPlayerToSurvivingSurface() {
        val moment = TodayMoment("m1", "content://media/nonexistent", 15_000_000, "now", "Proxy")
        val source = object : TodaySource {
            override val latest = MutableStateFlow(moment)
            override suspend fun refresh(momentId: String) = TodayAssetResult.Playable(moment)
            override suspend fun markViewed(momentId: String, viewedAt: String) = Unit
        }
        lateinit var player: Media3MomentPlayer
        lateinit var today: TodayViewModel
        compose.runOnUiThread {
            player = Media3MomentPlayer(compose.activity)
            today = TodayViewModel(source, player)
            compose.activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) today.onStop()
            })
        }
        compose.setContent { MomentPlayerSurface(player) }
        compose.runOnIdle { today.play() }
        compose.waitForIdle()
        val surface = checkNotNull(findPlayerView(compose.activity.window.decorView))
        val first = surface.player
        assertNotNull(first)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle {
            assertNull(surface.player)
            today.play()
        }
        compose.waitForIdle()
        assertSame(surface, findPlayerView(compose.activity.window.decorView))
        assertNotNull(surface.player)
        assertNotSame(first, surface.player)
        assertSame(player.currentPlayer.value, surface.player)
        compose.runOnIdle { today.onStop() }
    }

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findPlayerView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
