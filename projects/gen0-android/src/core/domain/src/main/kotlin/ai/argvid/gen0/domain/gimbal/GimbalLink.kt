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

    /** Sets the per-axis position speed for subsequent moves, in motor RPM. */
    suspend fun setSpeed(rpm: Int): CommandReceipt

    /**
     * Continuous per-axis velocity in signed motor RPM (0 stops the axis).
     * Velocity mode owns the axes until zeroed; implementers must guarantee the
     * axes stop if updates stop arriving (velocity watchdog or equivalent).
     */
    suspend fun setVelocity(panRpm: Double, tiltRpm: Double): CommandReceipt
    suspend fun emergencyStop(reason: EStopReason): CommandReceipt
    suspend fun disconnect()
}
