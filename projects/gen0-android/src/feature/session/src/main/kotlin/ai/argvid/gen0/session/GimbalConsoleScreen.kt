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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
                Text("Gimbal Console", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Gen0 capture control", style = MaterialTheme.typography.bodyMedium)
            }
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                Text("SIMULATOR", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
            }
        }

        ConnectionCard(state)

        if (state.candidates.isEmpty()) {
            Button(
                onClick = { onAction(GimbalConsoleAction.Scan) },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Scan for simulated gimbals" },
            ) {
                Text("Scan for simulator")
            }
        } else if (state.connection == GimbalConnectionState.Disconnected) {
            state.candidates.forEach { candidate ->
                Button(
                    onClick = { onAction(GimbalConsoleAction.Connect(candidate.id)) },
                    enabled = !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Connect to ${candidate.displayName}" },
                ) {
                    Text("Connect ${candidate.displayName}")
                }
            }
        }

        TelemetryCard(state)
        NudgePad(state.nudgeEnabled && !state.isBusy, onAction)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onAction(GimbalConsoleAction.Home) },
                enabled = state.homeEnabled && !state.isBusy,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Move gimbal to home position" },
            ) {
                Text("Home")
            }
            OutlinedButton(
                onClick = { onAction(GimbalConsoleAction.Hold) },
                enabled = state.holdEnabled && !state.isBusy,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Hold gimbal position" },
            ) {
                Text("Hold")
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
                .semantics { contentDescription = "Emergency stop gimbal motion" },
        ) {
            Text("EMERGENCY STOP", fontWeight = FontWeight.Bold)
        }

        if (state.connection != GimbalConnectionState.Disconnected) {
            OutlinedButton(
                onClick = { onAction(GimbalConsoleAction.Disconnect) },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Disconnect gimbal simulator" },
            ) {
                Text("Disconnect")
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
            Text("Connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusLine("State", state.connection.displayName())
            StatusLine("Motion", state.motion.displayName())
            StatusLine("Device", state.candidates.firstOrNull()?.displayName ?: "Not selected")
            StatusLine("Firmware", state.capability?.firmwareVersion ?: "—")
            StatusLine("Protocol", state.capability?.protocolVersion ?: "—")
        }
    }
}

@Composable
private fun TelemetryCard(state: GimbalConsoleUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Telemetry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusLine("Pan", "%.1f°".format(state.telemetry.panDeg))
            StatusLine("Tilt", "%.1f°".format(state.telemetry.tiltDeg))
            StatusLine("Temperature", "%.1f°C".format(state.telemetry.temperatureC))
            StatusLine("Fault", state.telemetry.fault ?: "None")
            StatusLine("Last ack", state.telemetry.lastAckSeq?.toString() ?: "—")
        }
    }
}

@Composable
private fun NudgePad(
    enabled: Boolean,
    onAction: (GimbalConsoleAction) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        NudgeButton("Tilt up", "↑", enabled) { onAction(GimbalConsoleAction.Nudge(0.0, 5.0)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NudgeButton("Pan left", "←", enabled) { onAction(GimbalConsoleAction.Nudge(-5.0, 0.0)) }
            Text("5° nudge", style = MaterialTheme.typography.labelLarge)
            NudgeButton("Pan right", "→", enabled) { onAction(GimbalConsoleAction.Nudge(5.0, 0.0)) }
        }
        NudgeButton("Tilt down", "↓", enabled) { onAction(GimbalConsoleAction.Nudge(0.0, -5.0)) }
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
        modifier = Modifier.semantics { contentDescription = "$description by 5 degrees" },
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
    GimbalConnectionState.Disconnected -> "Disconnected"
    GimbalConnectionState.Discovering -> "Discovering"
    GimbalConnectionState.Connecting -> "Connecting"
    GimbalConnectionState.Ready -> "Ready"
}

private fun GimbalMotionState.displayName(): String = when (this) {
    GimbalMotionState.Idle -> "Idle"
    GimbalMotionState.Moving -> "Moving"
    GimbalMotionState.Settling -> "Settling"
    GimbalMotionState.Holding -> "Holding"
    GimbalMotionState.Stalled -> "Stalled"
    GimbalMotionState.Fault -> "Fault"
}
