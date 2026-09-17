package ai.argvid.gen0

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class ReleaseJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchAndNavigateToTruthfulTodaySurface() {
        compose.onNodeWithText("Session").assertExists()
        compose.onNodeWithText("启用语音 STOP").assertDoesNotExist()
        compose.onNodeWithText("隐私遮罩预览").assertDoesNotExist()

        compose.onNodeWithText("Today").performClick()

        compose.onNodeWithContentDescription("Today 页面").assertExists()
    }
}
