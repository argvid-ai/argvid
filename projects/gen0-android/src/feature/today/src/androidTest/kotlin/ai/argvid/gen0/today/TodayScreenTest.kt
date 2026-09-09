package ai.argvid.gen0.today

import ai.argvid.gen0.media.catalog.TodayMoment
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TodayScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun anOlderVideoCanBeSelectedFromTheLibrary() {
        val old = TodayMoment("old", "content://moment/1", 15_000_000, "2026-08-29T18:00:00Z", "PROXY")
        val recent = old.copy(id = "recent", mediaUri = "content://moment/2",
            createdAt = "2026-08-29T19:00:00Z")
        var selected: String? = null
        compose.setContent {
            TodayScreen(
                state = TodayUiState.Ready(recent, false),
                moments = listOf(recent, old),
                onSelectMoment = { selected = it },
                onPlay = {}, onRetry = {}, onDeleteLocal = {},
            )
        }
        compose.onNodeWithContentDescription("Today 页面")
            .performScrollToNode(hasContentDescription("选择片段 old"))
        compose.onNodeWithContentDescription("选择片段 old").performClick()
        compose.runOnIdle { assertEquals("old", selected) }
    }

    @Test
    fun confirmationIdentifiesTheOriginalClipAfterTheSelectionChanges() {
        compose.setContent {
            TodayScreen(
                state = TodayUiState.Ready(
                    TodayMoment("new", "content://moment/2", 15_000_000, "2026-08-29T19:00:00Z", "PROXY"),
                    false,
                ),
                deletionState = DeletionUiState.Confirm("old", "2026-08-29T18:00:00Z"),
                onPlay = {}, onRetry = {}, onDeleteLocal = {},
            )
        }
        compose.onNodeWithText("片段编号：old").assertExists()
    }

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
