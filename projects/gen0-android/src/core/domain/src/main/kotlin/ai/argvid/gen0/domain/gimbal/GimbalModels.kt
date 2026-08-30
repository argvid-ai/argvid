package ai.argvid.gen0.domain.gimbal

@JvmInline
value class GimbalDeviceId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

data class GimbalCandidate(
    val id: GimbalDeviceId,
    val displayName: String,
    val isSimulator: Boolean,
)

data class GimbalCapability(
    val panRangeDeg: ClosedFloatingPointRange<Double>,
    val tiltRangeDeg: ClosedFloatingPointRange<Double>?,
    val maxDegreesPerSecond: Double,
    val supportedModes: Set<GimbalMode>,
    val watchdogMs: Int,
    val firmwareVersion: String,
    val protocolVersion: String,
) {
    init {
        require(maxDegreesPerSecond > 0.0)
        require(watchdogMs in 1..500)
    }

    companion object {
        fun gen05Simulator() = GimbalCapability(
            panRangeDeg = -90.0..90.0,
            tiltRangeDeg = -45.0..45.0,
            maxDegreesPerSecond = 30.0,
            supportedModes = setOf(GimbalMode.Manual, GimbalMode.Hold, GimbalMode.Home),
            watchdogMs = 400,
            firmwareVersion = "simulator-1",
            protocolVersion = "semantic-sim-1",
        )
    }
}

data class SemanticSetpoint(
    val seq: UShort,
    val panDeg: Double,
    val tiltDeg: Double,
    val maxDegreesPerSecond: Double = 30.0,
    val deadlineMs: Int = 400,
)

data class CommandReceipt(
    val ackSeq: UShort,
    val acceptedAtUs: Long,
)

data class GimbalTelemetry(
    val panDeg: Double = 0.0,
    val tiltDeg: Double = 0.0,
    val temperatureC: Double = 25.0,
    val fault: String? = null,
    val lastAckSeq: UShort? = null,
)

enum class GimbalMode {
    Manual,
    Hold,
    Home,
    Scan,
    Track,
}

enum class EStopReason {
    UserRequested,
    UnsafeMotion,
}

enum class GimbalCommandError {
    NotReady,
    DeviceNotFound,
    PanOutOfRange,
    TiltOutOfRange,
    SpeedOutOfRange,
    DeadlineInvalid,
    UnsupportedMode,
}

class GimbalCommandException(
    val reason: GimbalCommandError,
) : IllegalStateException(reason.name)

sealed interface GimbalEvent {
    data class MotionChanged(val state: GimbalMotionState) : GimbalEvent
    data class CommandRejected(val reason: GimbalCommandError) : GimbalEvent
    data class EmergencyStopped(val reason: EStopReason) : GimbalEvent
    data object LostContactHold : GimbalEvent
}
