package ai.argvid.gen0.session

import ai.argvid.gen0.domain.gimbal.GimbalConnectionState
import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.session.PauseReason
import ai.argvid.gen0.domain.session.SessionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SessionRoute(
    viewModel: SessionViewModel,
    modifier: Modifier = Modifier,
    onPermissionRequest: (AppPermission) -> Unit = {},
    previewContent: @Composable () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.permissionRequest) {
        state.permissionRequest?.let(onPermissionRequest)
    }
    SessionScreen(state, viewModel::onAction, modifier, previewContent)
}

@Composable
fun SessionScreen(
    state: SessionUiState,
    onAction: (SessionAction) -> Unit,
    modifier: Modifier = Modifier,
    previewContent: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Gen0 Camera Session", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(state.statusText, style = MaterialTheme.typography.titleMedium)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (state.previewVisible) previewContent()
            Text(
                if (state.previewVisible) "相机预览 · 无自动人脸遮罩" else "预览已关闭",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("采集状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                StatusLine("会话", state.sessionState.displayName())
                StatusLine("有效时长", "%.1f 秒".format(state.effectiveDurationUs / 1_000_000.0))
                StatusLine("代理规格", state.proxyProfile)
                if (state.warmupRemainingUs > 0) {
                    StatusLine("15 秒缓冲", "还需 %.1f 秒".format(state.warmupRemainingUs / 1_000_000.0))
                } else {
                    StatusLine("15 秒缓冲", "已就绪")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("云台语义模拟 · 非物理设备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                StatusLine("连接", state.gimbal.connection.displayName())
                StatusLine("运动", state.gimbal.motion.displayName())
                StatusLine("温度", "%.1f°C".format(state.gimbal.temperatureC))
            }
        }

        Button(
            onClick = { onAction(SessionAction.Rescue) },
            enabled = state.rescueEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .semantics { contentDescription = "救回最近15秒" },
        ) {
            Text("救回最近 15 秒", fontWeight = FontWeight.Bold)
        }

        Button(
            onClick = { onAction(SessionAction.Stop) },
            enabled = state.stopEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .semantics { contentDescription = "停止并清除采集缓冲" },
        ) {
            Text("STOP · 停止并清除", fontWeight = FontWeight.Bold)
        }

        if (state.showSaveFailure) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onAction(SessionAction.RetrySave) }, modifier = Modifier.weight(1f)) {
                    Text("重试保存")
                }
                OutlinedButton(onClick = { onAction(SessionAction.AbandonSave) }, modifier = Modifier.weight(1f)) {
                    Text("放弃")
                }
            }
        }

        if (state.showCatalogFailure) {
            Button(onClick = { onAction(SessionAction.RetrySave) }, modifier = Modifier.fillMaxWidth()) {
                Text("重试本地记录")
            }
        }

        if (state.showCleanupFailure) {
            Button(onClick = { onAction(SessionAction.RetryCleanup) }, modifier = Modifier.fillMaxWidth()) {
                Text("重试暂存清理")
            }
        }

        if (state.resumeConfirmationRequired) {
            Button(onClick = { onAction(SessionAction.ConfirmResume) }, modifier = Modifier.fillMaxWidth()) {
                Text("确认并重新开始")
            }
        }

        OutlinedButton(
            onClick = { onAction(SessionAction.StartPreflight) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("开始预检 / 相机权限")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onAction(SessionAction.ConnectGimbal) }, modifier = Modifier.weight(1f)) {
                Text("云台模拟说明")
            }
        }

        state.permissionRequest?.let { permission ->
            Text("等待${permission.displayName()}权限结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun SessionState.displayName(): String = when (this) {
    SessionState.Idle -> "待机"
    SessionState.Preflight -> "预检"
    SessionState.Calibrating -> "校准"
    SessionState.Running -> "采集中"
    SessionState.Ending -> "正在停止"
    SessionState.Ended -> "已结束"
    is SessionState.Paused -> when (reason) {
        PauseReason.Privacy -> "隐私暂停"
        PauseReason.Interruption -> "中断暂停"
        PauseReason.Motion -> "云台运动暂停"
        PauseReason.UserStop -> "用户停止"
    }
    is SessionState.Degraded -> "降级"
}

private fun GimbalConnectionState.displayName(): String = when (this) {
    GimbalConnectionState.Disconnected -> "未连接"
    GimbalConnectionState.Discovering -> "搜索中"
    GimbalConnectionState.Connecting -> "连接中"
    GimbalConnectionState.Ready -> "就绪"
}

private fun GimbalMotionState.displayName(): String = when (this) {
    GimbalMotionState.Idle -> "静止"
    GimbalMotionState.Moving -> "移动中"
    GimbalMotionState.Settling -> "稳定中"
    GimbalMotionState.Holding -> "保持"
    GimbalMotionState.Stalled -> "卡滞"
    GimbalMotionState.Fault -> "故障"
}

private fun AppPermission.displayName(): String = when (this) {
    AppPermission.Camera -> "相机"
}
