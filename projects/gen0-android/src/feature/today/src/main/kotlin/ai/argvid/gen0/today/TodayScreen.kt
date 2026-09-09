package ai.argvid.gen0.today

import ai.argvid.gen0.media.catalog.TodayMoment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

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
    moments: List<TodayMoment> = emptyList(),
    onSelectMoment: (String) -> Unit = {},
    playerContent: @Composable () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selectedId = when (state) {
        is TodayUiState.Ready -> state.moment.id
        is TodayUiState.Loading -> state.momentId
        is TodayUiState.AssetMissing -> state.momentId
        is TodayUiState.RetryableError -> state.momentId
        TodayUiState.Empty -> null
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Today 页面" },
        state = listState,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("selected-clip") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Today", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                when (state) {
                    TodayUiState.Empty -> Text("还没有已保存的片段")
                    is TodayUiState.Loading -> Text("正在核对所选片段…")
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
                        Text("所选片段 · ${state.moment.qualityTier}")
                        Text("保存时间：${displaySavedTime(state.moment.createdAt)}")
                        Text("片段编号：${state.moment.id.take(8)}")
                        if (state.moment.cleanupPending) Text("暂存副本待清理；删除本地片段会一并清理")
                        Button(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.isPlaying) "正在播放" else "播放片段")
                        }
                        OutlinedButton(
                            onClick = onDeleteLocal,
                            enabled = deleteEnabled && deletionState.canRequestDeletion,
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
                    is DeletionUiState.Deleting -> Text("正在删除片段 ${deletionState.momentId.take(8)}…")
                    is DeletionUiState.Complete -> {
                        Text("片段 ${deletionState.momentId.take(8)} 已从本地删除")
                        OutlinedButton(onClick = onClearRecord) { Text("清除此删除记录") }
                    }
                    is DeletionUiState.RetryRequired -> {
                        Text("片段 ${deletionState.momentId.take(8)} 删除未完成，请先重试")
                        Button(onClick = onRetryDelete) { Text("重试本地删除") }
                    }
                    is DeletionUiState.RecordCleared -> Text("片段 ${deletionState.momentId.take(8)} 的记录已清除")
                }
            }
        }
        item("library-heading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已保存视频（${moments.size}）", style = MaterialTheme.typography.titleMedium)
                Text("按保存时间从新到旧排列。选择一条视频后可播放或删除。")
            }
        }
        items(moments, key = { "clip-${it.id}" }) { moment ->
            OutlinedButton(
                onClick = {
                    onSelectMoment(moment.id)
                    scope.launch { listState.animateScrollToItem(0) }
                },
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = "选择片段 ${moment.id}" },
            ) {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(displaySavedTime(moment.createdAt), fontWeight = FontWeight.Bold)
                    Text("%.1f 秒 · %s".format(moment.durationUs / 1_000_000.0, moment.qualityTier))
                    Text("片段编号：${moment.id.take(8)}")
                    Text(if (selectedId == moment.id) "已选中" else "选择此视频")
                }
            }
        }
    }

    if (deletionState is DeletionUiState.Confirm) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("删除本地片段？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    deletionState.createdAt?.let { Text("保存时间：${displaySavedTime(it)}") }
                    Text("片段编号：${deletionState.momentId.take(8)}")
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

private fun displaySavedTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
