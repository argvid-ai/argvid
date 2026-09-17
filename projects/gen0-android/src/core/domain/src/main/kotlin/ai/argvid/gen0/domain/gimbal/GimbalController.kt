package ai.argvid.gen0.domain.gimbal

import kotlinx.coroutines.withTimeoutOrNull

sealed interface CommandFailure {
    data object CapabilityUnavailable : CommandFailure
    data object TiltUnsupported : CommandFailure
    data object PanOutOfRange : CommandFailure
    data object TiltOutOfRange : CommandFailure
    data object TelemetryStale : CommandFailure
    data class AckMismatch(val expected: UShort, val actual: UShort) : CommandFailure
    data class LinkRejected(val reason: GimbalCommandError) : CommandFailure
}

sealed interface CommandResult {
    data class Accepted(val receipt: CommandReceipt) : CommandResult
    data class Rejected(val reason: CommandFailure) : CommandResult
    data object TimedOut : CommandResult
}

class GimbalController(
    val link: GimbalLink,
    private val commandTimeoutMs: Int = 400,
    initialSeq: UShort = 0u,
    private val clock: ai.argvid.gen0.domain.time.MonotonicClock =
        ai.argvid.gen0.domain.time.MonotonicClock { System.nanoTime() / 1_000 },
    private val telemetryStaleMs: Long = 3_000L,
) {
    private var capability: GimbalCapability? = null
    private var sequence = initialSeq

    init {
        require(commandTimeoutMs > 0)
        require(telemetryStaleMs > 0)
    }

    suspend fun scan(): List<GimbalCandidate> = link.scan()

    suspend fun connect(id: GimbalDeviceId): GimbalCapability = link.connect(id).also {
        capability = it
    }

    suspend fun disconnect() {
        link.disconnect()
        capability = null
    }

    suspend fun nudge(
        panDeltaDeg: Double,
        tiltDeltaDeg: Double,
    ): CommandResult {
        val currentCapability = capability ?: return CommandResult.Rejected(CommandFailure.CapabilityUnavailable)
        if (currentCapability.tiltRangeDeg == null && tiltDeltaDeg != 0.0) {
            return CommandResult.Rejected(CommandFailure.TiltUnsupported)
        }

        val current = link.telemetry.value
        // Relative nudges must anchor to fresh measured angles: stale or never-
        // measured telemetry would turn the delta into an unbounded absolute jump.
        val measuredAtMs = current.measuredAtMs
        if (measuredAtMs == 0L || clock.nowUs() / 1_000 - measuredAtMs > telemetryStaleMs) {
            return CommandResult.Rejected(CommandFailure.TelemetryStale)
        }
        val targetPan = current.panDeg + panDeltaDeg
        val targetTilt = current.tiltDeg + tiltDeltaDeg
        if (targetPan !in currentCapability.panRangeDeg) {
            return CommandResult.Rejected(CommandFailure.PanOutOfRange)
        }
        val tiltRange = currentCapability.tiltRangeDeg
        if (tiltRange != null && targetTilt !in tiltRange) {
            return CommandResult.Rejected(CommandFailure.TiltOutOfRange)
        }

        val expectedSeq = nextSeq()
        val timeoutMs = minOf(commandTimeoutMs, currentCapability.watchdogMs)
        val setpoint = SemanticSetpoint(
            seq = expectedSeq,
            panDeg = targetPan,
            tiltDeg = targetTilt,
            maxDegreesPerSecond = currentCapability.maxDegreesPerSecond,
            deadlineMs = timeoutMs,
        )
        val receipt = try {
            withTimeoutOrNull(timeoutMs.toLong()) { link.send(setpoint) }
        } catch (error: GimbalCommandException) {
            return CommandResult.Rejected(CommandFailure.LinkRejected(error.reason))
        } ?: return CommandResult.TimedOut

        return if (receipt.ackSeq == expectedSeq) {
            CommandResult.Accepted(receipt)
        } else {
            CommandResult.Rejected(CommandFailure.AckMismatch(expectedSeq, receipt.ackSeq))
        }
    }

    suspend fun home(): CommandResult = executeControl { link.setMode(GimbalMode.Home) }

    suspend fun hold(): CommandResult = executeControl { link.setMode(GimbalMode.Hold) }

    /**
     * Absolute target move. Unlike [nudge] it does not anchor on measured
     * telemetry, so it stays available when the position reading is stale —
     * the right primitive for direct position input (sliders, presets).
     */
    suspend fun moveTo(panDeg: Double, tiltDeg: Double): CommandResult {
        val currentCapability = capability ?: return CommandResult.Rejected(CommandFailure.CapabilityUnavailable)
        if (panDeg !in currentCapability.panRangeDeg) return CommandResult.Rejected(CommandFailure.PanOutOfRange)
        val tiltRange = currentCapability.tiltRangeDeg
        if (tiltRange != null && tiltDeg !in tiltRange) return CommandResult.Rejected(CommandFailure.TiltOutOfRange)

        val expectedSeq = nextSeq()
        val timeoutMs = minOf(commandTimeoutMs, currentCapability.watchdogMs)
        val setpoint = SemanticSetpoint(
            seq = expectedSeq,
            panDeg = panDeg,
            tiltDeg = tiltDeg,
            maxDegreesPerSecond = currentCapability.maxDegreesPerSecond,
            deadlineMs = timeoutMs,
        )
        val receipt = try {
            withTimeoutOrNull(timeoutMs.toLong()) { link.send(setpoint) }
        } catch (error: GimbalCommandException) {
            return CommandResult.Rejected(CommandFailure.LinkRejected(error.reason))
        } ?: return CommandResult.TimedOut

        return if (receipt.ackSeq == expectedSeq) {
            CommandResult.Accepted(receipt)
        } else {
            CommandResult.Rejected(CommandFailure.AckMismatch(expectedSeq, receipt.ackSeq))
        }
    }

    suspend fun setSpeed(rpm: Int): CommandResult {
        if (rpm !in 1..MAX_SPEED_RPM) return CommandResult.Rejected(
            CommandFailure.LinkRejected(GimbalCommandError.SpeedOutOfRange),
        )
        val capability = capability ?: return CommandResult.Rejected(CommandFailure.CapabilityUnavailable)
        val timeoutMs = minOf(commandTimeoutMs, capability.watchdogMs)
        val receipt = try {
            withTimeoutOrNull(timeoutMs.toLong()) { link.setSpeed(rpm) }
        } catch (error: GimbalCommandException) {
            return CommandResult.Rejected(CommandFailure.LinkRejected(error.reason))
        } ?: return CommandResult.TimedOut
        return CommandResult.Accepted(receipt)
    }

    suspend fun emergencyStop(reason: EStopReason = EStopReason.UserRequested): CommandResult {
        val receipt = try {
            withTimeoutOrNull(commandTimeoutMs.toLong()) { link.emergencyStop(reason) }
        } catch (error: GimbalCommandException) {
            return CommandResult.Rejected(CommandFailure.LinkRejected(error.reason))
        } ?: return CommandResult.TimedOut
        return CommandResult.Accepted(receipt)
    }

    private suspend fun executeControl(command: suspend () -> CommandReceipt): CommandResult {
        val currentCapability = capability ?: return CommandResult.Rejected(CommandFailure.CapabilityUnavailable)
        val timeoutMs = minOf(commandTimeoutMs, currentCapability.watchdogMs)
        val receipt = try {
            withTimeoutOrNull(timeoutMs.toLong()) { command() }
        } catch (error: GimbalCommandException) {
            return CommandResult.Rejected(CommandFailure.LinkRejected(error.reason))
        } ?: return CommandResult.TimedOut
        return CommandResult.Accepted(receipt)
    }

    private fun nextSeq(): UShort {
        sequence = (sequence.toInt() + 1).toUShort()
        return sequence
    }

    private companion object {
        /** Firmware protocol cap for set_speed. */
        const val MAX_SPEED_RPM = 300
    }
}
