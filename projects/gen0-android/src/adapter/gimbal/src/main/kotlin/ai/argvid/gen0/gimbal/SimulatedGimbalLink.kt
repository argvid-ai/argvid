package ai.argvid.gen0.gimbal

import ai.argvid.gen0.domain.gimbal.CommandReceipt
import ai.argvid.gen0.domain.gimbal.EStopReason
import ai.argvid.gen0.domain.gimbal.GimbalCandidate
import ai.argvid.gen0.domain.gimbal.GimbalCapability
import ai.argvid.gen0.domain.gimbal.GimbalCommandError
import ai.argvid.gen0.domain.gimbal.GimbalCommandException
import ai.argvid.gen0.domain.gimbal.GimbalConnectionState
import ai.argvid.gen0.domain.gimbal.GimbalDeviceId
import ai.argvid.gen0.domain.gimbal.GimbalEvent
import ai.argvid.gen0.domain.gimbal.GimbalLink
import ai.argvid.gen0.domain.gimbal.GimbalMode
import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.gimbal.GimbalTelemetry
import ai.argvid.gen0.domain.gimbal.SemanticSetpoint
import ai.argvid.gen0.domain.time.MonotonicClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class SimulatedGimbalLink(
    private val clock: MonotonicClock,
    private val scheduler: GimbalScheduler,
    private val capability: GimbalCapability = GimbalCapability.gen05Simulator(),
    private val afterMotionScheduled: () -> Unit = {},
) : GimbalLink {
    private val simulatorId = GimbalDeviceId("sim-1")
    private val mutableConnection = MutableStateFlow(GimbalConnectionState.Disconnected)
    private val mutableMotion = MutableStateFlow(GimbalMotionState.Idle)
    private val mutableTelemetry = MutableStateFlow(GimbalTelemetry())
    private val eventBus = MutableSharedFlow<GimbalEvent>(extraBufferCapacity = 32)
    private var contactGeneration = 0L
    private var motionGeneration = 0L
    private var controlSeq = 0u.toUShort()

    override val connection: StateFlow<GimbalConnectionState> = mutableConnection.asStateFlow()
    override val motion: StateFlow<GimbalMotionState> = mutableMotion.asStateFlow()
    override val telemetry: StateFlow<GimbalTelemetry> = mutableTelemetry.asStateFlow()
    override val events: Flow<GimbalEvent> = eventBus.asSharedFlow()

    override suspend fun scan(): List<GimbalCandidate> {
        mutableConnection.value = GimbalConnectionState.Discovering
        mutableConnection.value = GimbalConnectionState.Disconnected
        return listOf(GimbalCandidate(simulatorId, "Gen0.5 Simulator", isSimulator = true))
    }

    override suspend fun connect(id: GimbalDeviceId): GimbalCapability {
        if (id != simulatorId) reject(GimbalCommandError.DeviceNotFound)
        motionGeneration++
        mutableConnection.value = GimbalConnectionState.Connecting
        mutableMotion.value = GimbalMotionState.Idle
        mutableTelemetry.value = GimbalTelemetry()
        mutableConnection.value = GimbalConnectionState.Ready
        refreshWatchdog()
        return capability
    }

    override suspend fun send(setpoint: SemanticSetpoint): CommandReceipt {
        requireReady()
        if (setpoint.panDeg !in capability.panRangeDeg) reject(GimbalCommandError.PanOutOfRange)
        val tiltRange = capability.tiltRangeDeg
        if ((tiltRange == null && setpoint.tiltDeg != 0.0) || (tiltRange != null && setpoint.tiltDeg !in tiltRange)) {
            reject(GimbalCommandError.TiltOutOfRange)
        }
        if (setpoint.maxDegreesPerSecond <= 0.0 || setpoint.maxDegreesPerSecond > capability.maxDegreesPerSecond) {
            reject(GimbalCommandError.SpeedOutOfRange)
        }
        if (setpoint.deadlineMs !in 1..capability.watchdogMs) reject(GimbalCommandError.DeadlineInvalid)

        refreshWatchdog()
        mutableTelemetry.value = mutableTelemetry.value.copy(
            panDeg = setpoint.panDeg,
            tiltDeg = setpoint.tiltDeg,
            lastAckSeq = setpoint.seq,
        )
        scheduleMotionCycle()
        return CommandReceipt(setpoint.seq, clock.nowUs())
    }

    override suspend fun setMode(mode: GimbalMode): CommandReceipt {
        requireReady()
        if (mode !in capability.supportedModes) reject(GimbalCommandError.UnsupportedMode)
        motionGeneration++
        refreshWatchdog()
        val seq = nextControlSeq()
        mutableTelemetry.value = mutableTelemetry.value.copy(lastAckSeq = seq)
        when (mode) {
            GimbalMode.Manual -> setMotion(GimbalMotionState.Idle)
            GimbalMode.Hold -> setMotion(GimbalMotionState.Holding)
            GimbalMode.Home -> {
                mutableTelemetry.value = mutableTelemetry.value.copy(panDeg = 0.0, tiltDeg = 0.0)
                scheduleMotionCycle()
            }
            GimbalMode.Scan,
            GimbalMode.Track,
            -> reject(GimbalCommandError.UnsupportedMode)
        }
        return CommandReceipt(seq, clock.nowUs())
    }

    override suspend fun emergencyStop(reason: EStopReason): CommandReceipt {
        requireReady()
        contactGeneration++
        motionGeneration++
        val seq = nextControlSeq()
        mutableTelemetry.value = mutableTelemetry.value.copy(fault = "Emergency stop", lastAckSeq = seq)
        setMotion(GimbalMotionState.Fault)
        eventBus.tryEmit(GimbalEvent.EmergencyStopped(reason))
        return CommandReceipt(seq, clock.nowUs())
    }

    override suspend fun disconnect() {
        contactGeneration++
        motionGeneration++
        if (mutableConnection.value != GimbalConnectionState.Disconnected) {
            setMotion(GimbalMotionState.Holding)
        }
        mutableConnection.value = GimbalConnectionState.Disconnected
    }

    private fun requireReady() {
        if (mutableConnection.value != GimbalConnectionState.Ready) reject(GimbalCommandError.NotReady)
    }

    private fun reject(reason: GimbalCommandError): Nothing {
        eventBus.tryEmit(GimbalEvent.CommandRejected(reason))
        throw GimbalCommandException(reason)
    }

    private fun refreshWatchdog() {
        val generation = ++contactGeneration
        val deadlineUs = clock.nowUs() + capability.watchdogMs * 1_000L
        scheduler.at(deadlineUs) {
            if (generation == contactGeneration && mutableConnection.value == GimbalConnectionState.Ready) {
                motionGeneration++
                mutableTelemetry.value = mutableTelemetry.value.copy(fault = "Lost contact hold")
                setMotion(GimbalMotionState.Holding)
                eventBus.tryEmit(GimbalEvent.LostContactHold)
            }
        }
    }

    private fun scheduleMotionCycle() {
        val generation = ++motionGeneration
        setMotion(GimbalMotionState.Moving)
        scheduler.at(clock.nowUs() + 100_000) {
            if (generation == motionGeneration) setMotion(GimbalMotionState.Settling)
        }
        scheduler.at(clock.nowUs() + 300_000) {
            if (generation == motionGeneration) setMotion(GimbalMotionState.Idle)
        }
        afterMotionScheduled()
    }

    private fun setMotion(state: GimbalMotionState) {
        mutableMotion.value = state
        eventBus.tryEmit(GimbalEvent.MotionChanged(state))
    }

    private fun nextControlSeq(): UShort {
        controlSeq = (controlSeq.toInt() + 1).toUShort()
        return controlSeq
    }
}
