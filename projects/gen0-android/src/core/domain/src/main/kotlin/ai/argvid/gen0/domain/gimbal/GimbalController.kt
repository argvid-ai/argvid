package ai.argvid.gen0.domain.gimbal

import kotlinx.coroutines.withTimeoutOrNull

sealed interface CommandFailure {
    data object CapabilityUnavailable : CommandFailure
    data object TiltUnsupported : CommandFailure
    data object PanOutOfRange : CommandFailure
    data object TiltOutOfRange : CommandFailure
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
) {
    private var capability: GimbalCapability? = null
    private var sequence = initialSeq

    init {
        require(commandTimeoutMs > 0)
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
}
