package ai.argvid.gen0.domain.gimbal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface GimbalLink {
    val connection: StateFlow<GimbalConnectionState>
    val motion: StateFlow<GimbalMotionState>
    val telemetry: StateFlow<GimbalTelemetry>
    val events: Flow<GimbalEvent>

    suspend fun scan(): List<GimbalCandidate>
    suspend fun connect(id: GimbalDeviceId): GimbalCapability
    suspend fun send(setpoint: SemanticSetpoint): CommandReceipt
    suspend fun setMode(mode: GimbalMode): CommandReceipt
    suspend fun emergencyStop(reason: EStopReason): CommandReceipt
    suspend fun disconnect()
}
