package ai.argvid.gen0.session

import ai.argvid.gen0.domain.gimbal.GimbalConnectionState
import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
fun GimbalConsoleRoute(
    viewModel: GimbalConsoleViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    GimbalConsoleScreen(state, viewModel::onAction, modifier)
}

@Composable
fun GimbalConsoleScreen(
    state: GimbalConsoleUiState,
    onAction: (GimbalConsoleAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("云台控制台", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Gen0 采集控制", style = MaterialTheme.typography.bodyMedium)
            }
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    if (state.isSimulator) "模拟器" else "真实云台（BLE）",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        ConnectionCard(state)

        if (state.candidates.isEmpty()) {
            Button(
                onClick = { onAction(GimbalConsoleAction.Scan) },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "扫描云台设备" },
            ) {
                Text("扫描云台")
            }
        } else if (state.connection == GimbalConnectionState.Disconnected) {
            state.candidates.forEach { candidate ->
                Button(
                    onClick = { onAction(GimbalConsoleAction.Connect(candidate.id)) },
                    enabled = !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "连接 ${candidate.displayName}" },
                ) {
                    Text("连接 ${candidate.displayName}")
                }
            }
        }

        TelemetryCard(state)
        PositionControlCard(state, onAction)
        NudgePad(state.nudgeEnabled && !state.isBusy, state.stepDeg, onAction)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onAction(GimbalConsoleAction.Home) },
                enabled = state.homeEnabled && !state.isBusy,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "云台回原点" },
            ) {
                Text("回中")
            }
            OutlinedButton(
                onClick = { onAction(GimbalConsoleAction.Hold) },
                enabled = state.holdEnabled && !state.isBusy,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "保持云台当前位置" },
            ) {
                Text("保持")
            }
        }

        Button(
            onClick = { onAction(GimbalConsoleAction.EmergencyStop) },
            enabled = state.emergencyStopEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = "紧急停止云台运动" },
        ) {
            Text("紧急停止", fontWeight = FontWeight.Bold)
        }

        if (state.connection != GimbalConnectionState.Disconnected) {
            OutlinedButton(
                onClick = { onAction(GimbalConsoleAction.Disconnect) },
                // Reachable during a connecting handshake: disconnect aborts it and
                // is the user's way out of a slow or wedged connect.
                enabled = !state.isBusy || state.connection == GimbalConnectionState.Connecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "断开云台" },
            ) {
                Text("断开")
            }
        }

        state.message?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(message, modifier = Modifier.padding(16.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ConnectionCard(state: GimbalConsoleUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusLine("状态", state.connection.displayName())
            StatusLine("运动", state.motion.displayName())
            StatusLine("设备", state.candidates.firstOrNull()?.displayName ?: "未选择")
            StatusLine("固件", state.capability?.firmwareVersion ?: "—")
            StatusLine("协议", state.capability?.protocolVersion ?: "—")
        }
    }
}

@Composable
private fun TelemetryCard(state: GimbalConsoleUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("遥测", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusLine("水平 Pan", "%.1f°".format(state.telemetry.panDeg))
            StatusLine("俯仰 Tilt", "%.1f°".format(state.telemetry.tiltDeg))
            StatusLine("温度", "%.1f°C".format(state.telemetry.temperatureC))
            StatusLine("故障", state.telemetry.fault ?: "无")
            StatusLine("最近应答", state.telemetry.lastAckSeq?.toString() ?: "—")
        }
    }
}

@Composable
private fun NudgePad(
    enabled: Boolean,
    stepDeg: Int,
    onAction: (GimbalConsoleAction) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        val step = stepDeg.toDouble()
        NudgeButton("俯仰抬升", "↑", enabled) { onAction(GimbalConsoleAction.Nudge(0.0, step)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NudgeButton("水平左转", "←", enabled) { onAction(GimbalConsoleAction.Nudge(-step, 0.0)) }
            Text("$stepDeg° 步进", style = MaterialTheme.typography.labelLarge)
            NudgeButton("水平右转", "→", enabled) { onAction(GimbalConsoleAction.Nudge(step, 0.0)) }
        }
        NudgeButton("俯仰下压", "↓", enabled) { onAction(GimbalConsoleAction.Nudge(0.0, -step)) }
    }
}

/**
 * Direct position control: step-size chips for the nudge pad, a position-speed
 * slider (motor RPM, applied via set_speed), and two target-angle sliders that
 * issue an absolute move on release. Sliders follow the measured angles while
 * idle so they double as a live position readout.
 */
@Composable
private fun PositionControlCard(state: GimbalConsoleUiState, onAction: (GimbalConsoleAction) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("位置控制", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("步进", color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(1, 2, 5, 10).forEach { step ->
                    FilterChip(
                        selected = state.stepDeg == step,
                        onClick = { onAction(GimbalConsoleAction.SetStepDeg(step)) },
                        label = { Text("$step°") },
                    )
                }
            }

            var speedDragging by remember { mutableStateOf(false) }
            var speedValue by remember { mutableStateOf(state.speedRpm.toFloat()) }
            if (!speedDragging) speedValue = state.speedRpm.toFloat()
            Text(
                "位置速度 ${speedValue.roundToInt()} RPM",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = speedValue,
                onValueChange = {
                    speedDragging = true
                    speedValue = it
                },
                onValueChangeFinished = {
                    speedDragging = false
                    onAction(GimbalConsoleAction.SetSpeed(speedValue.roundToInt()))
                },
                valueRange = 10f..120f,
                steps = 10,
                enabled = state.nudgeEnabled,
            )

            var panDragging by remember { mutableStateOf(false) }
            var panTarget by remember { mutableStateOf(state.telemetry.panDeg.toFloat()) }
            if (!panDragging) panTarget = state.telemetry.panDeg.toFloat()
            Text(
                "水平目标 ${panTarget.roundToInt()}°（实测 ${"%.1f".format(state.telemetry.panDeg)}°）",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = panTarget,
                onValueChange = {
                    panDragging = true
                    panTarget = it
                },
                onValueChangeFinished = {
                    panDragging = false
                    onAction(
                        GimbalConsoleAction.MoveTo(
                            panDeg = panTarget.roundToInt().toDouble(),
                            tiltDeg = state.telemetry.tiltDeg.roundToInt().toDouble(),
                        ),
                    )
                },
                valueRange = -180f..180f,
                enabled = state.nudgeEnabled,
            )

            var tiltDragging by remember { mutableStateOf(false) }
            var tiltTarget by remember { mutableStateOf(state.telemetry.tiltDeg.toFloat()) }
            if (!tiltDragging) tiltTarget = state.telemetry.tiltDeg.toFloat()
            Text(
                "俯仰目标 ${tiltTarget.roundToInt()}°（实测 ${"%.1f".format(state.telemetry.tiltDeg)}°）",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = tiltTarget,
                onValueChange = {
                    tiltDragging = true
                    tiltTarget = it
                },
                onValueChangeFinished = {
                    tiltDragging = false
                    onAction(
                        GimbalConsoleAction.MoveTo(
                            panDeg = state.telemetry.panDeg.roundToInt().toDouble(),
                            tiltDeg = tiltTarget.roundToInt().toDouble(),
                        ),
                    )
                },
                valueRange = -90f..90f,
                enabled = state.nudgeEnabled,
            )
        }
    }
}

@Composable
private fun NudgeButton(
    description: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "$description 5 度" },
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun GimbalConnectionState.displayName(): String = when (this) {
    GimbalConnectionState.Disconnected -> "未连接"
    GimbalConnectionState.Discovering -> "扫描中"
    GimbalConnectionState.Connecting -> "连接中"
    GimbalConnectionState.Ready -> "就绪"
}

private fun GimbalMotionState.displayName(): String = when (this) {
    GimbalMotionState.Idle -> "静止"
    GimbalMotionState.Moving -> "运动中"
    GimbalMotionState.Settling -> "稳定中"
    GimbalMotionState.Holding -> "保持"
    GimbalMotionState.Stalled -> "堵转"
    GimbalMotionState.Fault -> "故障"
}
