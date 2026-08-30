package ai.argvid.gen0.session

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import ai.argvid.gen0.domain.gimbal.GimbalConnectionState
import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.session.PauseReason
import ai.argvid.gen0.domain.session.SessionState
import org.junit.Rule
import org.junit.Test

class SessionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun activeSessionAlwaysShowsExplicitStopAndTruthfulRescue() {
        compose.setContent { SessionScreen(state = runningState(), onAction = {}) }

        compose.onNodeWithContentDescription("停止并清除采集缓冲").assertIsEnabled()
        compose.onNodeWithContentDescription("救回最近15秒").assertIsEnabled()
        compose.onNodeWithText("已保存到相册").assertIsNotDisplayed()
    }

    @Test
    fun motionPauseShowsCountdownAndDisablesRescue() {
        compose.setContent {
            SessionScreen(
                state = runningState().copy(
                    sessionState = SessionState.Paused(PauseReason.Motion),
                    warmupRemainingUs = 12_000_000,
                    rescueEnabled = false,
                    statusText = "云台调整中",
                    gimbal = GimbalUiState(GimbalConnectionState.Ready, GimbalMotionState.Moving, 32.0),
                ),
                onAction = {},
            )
        }

        compose.onNodeWithText("云台调整中").assertExists()
        compose.onNodeWithText("还需 12.0 秒").assertExists()
        compose.onNodeWithContentDescription("救回最近15秒").assertIsNotEnabled()
        compose.onNodeWithContentDescription("停止并清除采集缓冲").assertIsEnabled()
    }

    private fun runningState() = SessionUiState(
        sessionState = SessionState.Running,
        previewVisible = true,
        effectiveDurationUs = 15_000_000,
        proxyProfile = "960×540 · 8 fps · JPEG 70%",
        gimbal = GimbalUiState(GimbalConnectionState.Ready, GimbalMotionState.Idle, 31.5),
        warmupRemainingUs = 0,
        rescueEnabled = true,
        stopEnabled = true,
        statusText = "最近15秒可救回",
    )
}
