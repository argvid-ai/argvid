package ai.argvid.gen0.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun TodayScreen(
    state: TodayUiState,
    deletionState: DeletionUiState = DeletionUiState.None,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onDeleteLocal: () -> Unit,
    onConfirmDelete: () -> Unit = {},
    onDismissDelete: () -> Unit = {},
    onRetryDelete: () -> Unit = {},
    onClearRecord: () -> Unit = {},
    deleteEnabled: Boolean = false,
    modifier: Modifier = Modifier,
    playerContent: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Today 页面" }
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Today", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        when (state) {
            TodayUiState.Empty -> Text("还没有已保存的片段")
            is TodayUiState.AssetMissing -> {
                Text("这个片段已不在设备中")
                Text("记录仍保留，但不会显示虚假的缩略图或播放按钮。")
            }
            is TodayUiState.RetryableError -> {
                Text("暂时无法核对片段")
                Button(onClick = onRetry) { Text("重试") }
            }
            is TodayUiState.Ready -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    playerContent()
                    if (!state.isPlaying) Text("片段已就绪")
                }
                Text("最近保存 · ${state.moment.qualityTier}")
                if (state.moment.cleanupPending) Text("暂存副本待清理；删除本地片段会一并清理")
                Button(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.isPlaying) "正在播放" else "播放片段")
                }
                OutlinedButton(
                    onClick = onDeleteLocal,
                    enabled = deleteEnabled,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "删除本地片段" },
                ) {
                    Text("删除本地片段")
                }
            }
        }
        when (deletionState) {
            DeletionUiState.None,
            is DeletionUiState.Confirm,
            -> Unit
            DeletionUiState.Deleting -> Text("正在删除本地片段…")
            is DeletionUiState.Complete -> {
                Text("本地片段已删除")
                OutlinedButton(onClick = onClearRecord) { Text("清除记录") }
            }
            is DeletionUiState.RetryRequired -> {
                Text("本地删除需要重试")
                Button(onClick = onRetryDelete) { Text("重试本地删除") }
            }
            DeletionUiState.RecordCleared -> Text("本地记录已清除")
        }
    }

    if (deletionState is DeletionUiState.Confirm) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("删除本地片段？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地媒体")
                    Text("将从设备相册和应用暂存区删除。")
                    Text("本地元数据记录")
                    Text("仍会保留，可另行清除记录。")
                }
            },
            confirmButton = { Button(onClick = onConfirmDelete) { Text("确认删除") } },
            dismissButton = { OutlinedButton(onClick = onDismissDelete) { Text("取消") } },
        )
    }
}
