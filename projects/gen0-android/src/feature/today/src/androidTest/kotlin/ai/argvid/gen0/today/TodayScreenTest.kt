package ai.argvid.gen0.today

import ai.argvid.gen0.media.catalog.TodayMoment
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class TodayScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun localDeleteActionHasExplicitAccessibilityLabel() {
        compose.setContent {
            TodayScreen(
                state = TodayUiState.Ready(
                    TodayMoment("m1", "content://moment/1", 15_000_000, "2026-08-29T18:00:00Z", "PROXY"),
                    isPlaying = false,
                ),
                onPlay = {},
                onRetry = {},
                onDeleteLocal = {},
            )
        }

        compose.onNodeWithContentDescription("删除本地片段").assertExists()
    }

    @Test
    fun confirmationDescribesOnlyLocalMediaAndMetadata() {
        compose.setContent {
            TodayScreen(
                state = TodayUiState.Ready(
                    TodayMoment("m1", "content://moment/1", 15_000_000, "2026-08-29T18:00:00Z", "PROXY"),
                    isPlaying = false,
                ),
                deletionState = DeletionUiState.Confirm("m1"),
                onPlay = {},
                onRetry = {},
                onDeleteLocal = {},
                onConfirmDelete = {},
                onDismissDelete = {},
            )
        }

        compose.onNodeWithText("本地媒体").assertExists()
        compose.onNodeWithText("本地元数据记录").assertExists()
        compose.onNodeWithText("云端副本").assertDoesNotExist()
    }
}
