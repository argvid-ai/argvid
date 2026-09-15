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
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GimbalLink bridge over the project-local F32C BLE protocol. This is the phase-C
 * integration adapter; it must remain an explicit opt-in path — the semantic
 * simulator stays the production default until a maintainer-approved bridge contract.
 *
 * Acknowledgement semantics (see the native-camera BLE integration contract):
 * - A CommandReceipt means the FF03 write chain was transport-accepted for the
 *   caller's sequence number; the bridge echoes the sequence itself and the receipt
 *   is NOT a device-confirmed motion report. GimbalTelemetry.lastAckSeq stays null
 *   because the device never acknowledges a sequence.
 * - GimbalCapability.watchdogMs is 400 only as the controller's command deadline;
 *   the F32C transport has no watchdog. The real safety net is the firmware's
 *   disconnect fail-stop (0 RPM on link loss, verified on hardware); an unexpected
 *   loss surfaces as GimbalEvent.LostContactHold with Holding motion.
 * - Measured angles come only from query_result(total_angle). Firmware
 *   gimbal_state/sys_status angles are targets and are never written to telemetry.
 * - The bridge does not translate deg/s to RPM; position moves run at the firmware's
 *   cached per-axis speed (default 60 RPM). maxDegreesPerSecond is a validation
 *   bound only.
 */
class F32cBleGimbalLink(
    private val transport: F32cBleTransport,
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
) : GimbalLink {
    private val mutableConnection = MutableStateFlow(GimbalConnectionState.Disconnected)
    private val mutableMotion = MutableStateFlow(GimbalMotionState.Idle)
    private val mutableTelemetry = MutableStateFlow(GimbalTelemetry())
    private val eventBus = MutableSharedFlow<GimbalEvent>(extraBufferCapacity = 64)
    private val commandMutex = Mutex()
    private val pollMutex = Mutex()
    private val reassembler = F32cFragmentReassembler()
    private var axisPan = DEFAULT_PAN_ID
    private var axisTilt = DEFAULT_TILT_ID
    private var axisSpeedRpm = mapOf<Int, Double>()
    private var pendingCommandResult: CompletableDeferred<Pair<Boolean, String>>? = null
    private var pendingScanResult: CompletableDeferred<F32cScanResult>? = null
    private var pendingGimbalStatus: CompletableDeferred<F32cGimbalStatus>? = null
    private var pendingBaselineAngles: Map<Int, CompletableDeferred<Double>>? = null
    private var pollerJob: Job? = null

    /** Velocity-mode state: dedup cache, last update stamp and the watchdog job. */
    @Volatile private var lastPanRpm: Double? = null
    @Volatile private var lastTiltRpm: Double? = null
    @Volatile private var velocityLastUpdatedAtMs = 0L
    private var velocityWatchdogJob: Job? = null

    /** True while a control command waits for its cmd_result; the poller stands down. */
    @Volatile private var awaitingCommandResult = false

    /** Latched by emergencyStop; only a fresh connect clears it. */
    @Volatile private var estopLatched = false

    override val connection: StateFlow<GimbalConnectionState> = mutableConnection.asStateFlow()
    override val motion: StateFlow<GimbalMotionState> = mutableMotion.asStateFlow()
    override val telemetry: StateFlow<GimbalTelemetry> = mutableTelemetry.asStateFlow()
    override val events: Flow<GimbalEvent> = eventBus.asSharedFlow()

    init {
        scope.launch {
            transport.notifications.collect { notification ->
                val complete = reassembler.accept(notification.payload, clock.nowUs() / 1_000)
                if (complete != null) dispatch(complete.toString(StandardCharsets.UTF_8))
            }
        }
        scope.launch {
            transport.connectionLost.collect {
                if (mutableConnection.value == GimbalConnectionState.Ready) {
                    // The firmware fail-stops both axes (0 RPM) on BLE loss; the bridge
                    // reports holding instead of pretending the link is still usable.
                    stopPoller()
                    mutableTelemetry.value = mutableTelemetry.value.copy(fault = "BLE connection lost")
                    mutableConnection.value = GimbalConnectionState.Disconnected
                    setMotion(GimbalMotionState.Holding)
                    eventBus.tryEmit(GimbalEvent.LostContactHold)
                }
            }
        }
    }

    override suspend fun scan(): List<GimbalCandidate> {
        mutableConnection.value = GimbalConnectionState.Discovering
        val devices = transport.scan()
        mutableConnection.value = GimbalConnectionState.Disconnected
        return devices.map { GimbalCandidate(GimbalDeviceId(it.id), it.name, isSimulator = false) }
    }

    override suspend fun connect(id: GimbalDeviceId): GimbalCapability {
        commandMutex.withLock {
            if (mutableConnection.value == GimbalConnectionState.Ready) reject(GimbalCommandError.NotReady)
            mutableConnection.value = GimbalConnectionState.Connecting
            try {
                transport.connect(id.value)
                // No hardcoded axis-id fallback: the roles come from the firmware's
                // own configuration (gimbal_state). Guessing ids could baseline
                // against decoy motors and make the first relative nudge a large
                // absolute jump on the real axes.
                val status = discoverMotorsAndAxes()
                    ?: reject(GimbalCommandError.DeviceNotFound)
                axisPan = status.panId
                axisTilt = status.tiltId
                axisSpeedRpm = emptyMap()
                mutableTelemetry.value = GimbalTelemetry()
                estopLatched = false
                lastPanRpm = null
                lastTiltRpm = null

                // Safety baseline: refuse Ready until both axes have a measured
                // angle. Without it a caller's first relative nudge would be
                // computed against (0,0) and could command a large absolute jump.
                awaitMeasuredBaseline()
                mutableConnection.value = GimbalConnectionState.Ready
                setMotion(GimbalMotionState.Idle)
            } catch (error: Throwable) {
                runCatching { transport.disconnect() }
                mutableConnection.value = GimbalConnectionState.Disconnected
                when (error) {
                    is GimbalCommandException -> throw error
                    // Cancellation must propagate so callers and structured scopes
                    // observe it instead of a misleading device error.
                    is kotlinx.coroutines.CancellationException -> throw error
                    else -> reject(GimbalCommandError.DeviceNotFound)
                }
            }
        }
        startPoller()
        return capability
    }

    override suspend fun send(setpoint: SemanticSetpoint): CommandReceipt = commandMutex.withLock {
        requireReady()
        if (setpoint.panDeg !in capability.panRangeDeg) reject(GimbalCommandError.PanOutOfRange)
        val tiltRange = capability.tiltRangeDeg
        if (tiltRange != null && setpoint.tiltDeg !in tiltRange) reject(GimbalCommandError.TiltOutOfRange)
        if (setpoint.maxDegreesPerSecond <= 0.0 || setpoint.maxDegreesPerSecond > capability.maxDegreesPerSecond) {
            reject(GimbalCommandError.SpeedOutOfRange)
        }
        if (setpoint.deadlineMs !in 1..capability.watchdogMs) reject(GimbalCommandError.DeadlineInvalid)

        // One gimbal-level command: the firmware sequences per-axis mode/enable/speed
        // itself over UART, clamps pan and rejects tilt targets beyond ±90°.
        writeAwaitingResult("""{"cmd":"move","pan":${setpoint.panDeg},"tilt":${setpoint.tiltDeg}}""")
        if (estopLatched) reject(GimbalCommandError.NotReady)
        enterMoving()
        refreshTelemetry()
        CommandReceipt(setpoint.seq, clock.nowUs())
    }

    override suspend fun setMode(mode: GimbalMode): CommandReceipt = commandMutex.withLock {
        requireReady()
        when (mode) {
            GimbalMode.Manual -> CommandReceipt(nextControlSeq(), clock.nowUs())
            GimbalMode.Hold -> {
                stopBothAxes()
                setMotion(GimbalMotionState.Holding)
                CommandReceipt(nextControlSeq(), clock.nowUs())
            }
            GimbalMode.Home -> {
                writeAwaitingResult("""{"cmd":"center"}""")
                if (estopLatched) reject(GimbalCommandError.NotReady)
                enterMoving()
                refreshTelemetry()
                CommandReceipt(nextControlSeq(), clock.nowUs())
            }
            GimbalMode.Scan,
            GimbalMode.Track,
            -> reject(GimbalCommandError.UnsupportedMode)
        }
    }

    override suspend fun setSpeed(rpm: Int): CommandReceipt = commandMutex.withLock {
        requireReady()
        if (rpm !in 1..MAX_SPEED_RPM) reject(GimbalCommandError.SpeedOutOfRange)
        // Per-axis single-motor commands; the firmware caches these as the
        // position speed for subsequent moves (and restores them after a link
        // loss). Fire both writes without waiting for cmd_result so the caller's
        // command budget is not consumed by two round trips.
        transport.writeCommand("""{"cmd":"set_speed","addr":$axisPan,"rpm":$rpm}""")
        transport.writeCommand("""{"cmd":"set_speed","addr":$axisTilt,"rpm":$rpm}""")
        CommandReceipt(nextControlSeq(), clock.nowUs())
    }

    /**
     * Continuous per-axis velocity via the firmware's queue-bypassing jog. Writes
     * are deduplicated per axis so a steady velocity costs no bus traffic. A
     * velocity watchdog stops both axes if updates stop arriving: unlike position
     * moves, a stale velocity command would keep the gimbal rotating forever.
     */
    override suspend fun setVelocity(panRpm: Double, tiltRpm: Double): CommandReceipt = commandMutex.withLock {
        requireReady()
        val pan = velocityCommand(panRpm, lastPanRpm, axisName = "pan")
        val tilt = velocityCommand(tiltRpm, lastTiltRpm, axisName = "tilt")
        lastPanRpm = panRpm
        lastTiltRpm = tiltRpm
        velocityLastUpdatedAtMs = clock.nowUs() / 1_000
        pan.forEach { transport.writeCommand(it) }
        tilt.forEach { transport.writeCommand(it) }
        if (panRpm != 0.0 || tiltRpm != 0.0) startVelocityWatchdog()
        setMotion(if (panRpm == 0.0 && tiltRpm == 0.0) GimbalMotionState.Idle else GimbalMotionState.Moving)
        CommandReceipt(nextControlSeq(), clock.nowUs())
    }

    /** Returns the jog write for one axis, or empty when the velocity is unchanged. */
    private fun velocityCommand(rpm: Double, previous: Double?, axisName: String): List<String> {
        if (rpm == previous) return emptyList()
        if (kotlin.math.abs(rpm) > MAX_SPEED_RPM) reject(GimbalCommandError.SpeedOutOfRange)
        val speed = kotlin.math.abs(rpm).toInt().coerceAtLeast(if (rpm == 0.0) 0 else 1)
        val direction = when {
            rpm > 0 -> 1
            rpm < 0 -> -1
            else -> 0
        }
        return listOf("""{"cmd":"jog","axis":"$axisName","dir":$direction,"speed":$speed}""")
    }

    /** Started lazily with the first non-zero velocity; stops axes when updates stall. */
    private fun startVelocityWatchdog() {
        if (velocityWatchdogJob?.isActive == true) return
        velocityWatchdogJob = scope.launch {
            while (true) {
                delay(VELOCITY_WATCHDOG_INTERVAL_MS)
                val lastNonZero = lastPanRpm != 0.0 || lastTiltRpm != 0.0
                val stale = clock.nowUs() / 1_000 - velocityLastUpdatedAtMs > VELOCITY_WATCHDOG_MS
                if (mutableConnection.value == GimbalConnectionState.Ready && lastNonZero && stale) {
                    runCatching { stopBothAxes() }
                    lastPanRpm = 0.0
                    lastTiltRpm = 0.0
                    mutableTelemetry.value = mutableTelemetry.value.copy(fault = "速度模式更新停滞，已自动停转")
                    setMotion(GimbalMotionState.Fault)
                }
            }
        }
    }

    override suspend fun emergencyStop(reason: EStopReason): CommandReceipt {
        // Deliberately NOT behind commandMutex: an in-flight send holds it for its
        // full write+result window while the controller cancels this call at 400ms.
        // The stop jogs queue behind at most one in-flight GATT write via the
        // transport's own write lock, so the stop reaches the firmware FIFO right
        // after any already-issued move.
        val state = mutableConnection.value
        if (state != GimbalConnectionState.Ready && state != GimbalConnectionState.Connecting) {
            reject(GimbalCommandError.NotReady)
        }
        if (state == GimbalConnectionState.Connecting) {
            // Abort the handshake; no motion command can be in flight yet, and this
            // makes the stop button meaningful during the whole connect window.
            runCatching { transport.disconnect() }
        } else {
            runCatching { stopBothAxes() }
        }
        estopLatched = true
        // Unblock a send that is mid cmd_result wait so it cannot override Fault.
        pendingCommandResult?.complete(true to "superseded by emergency stop")
        mutableTelemetry.value = mutableTelemetry.value.copy(fault = "Emergency stop")
        setMotion(GimbalMotionState.Fault)
        eventBus.tryEmit(GimbalEvent.EmergencyStopped(reason))
        return CommandReceipt(nextControlSeq(), clock.nowUs())
    }

    override suspend fun disconnect() {
        if (mutableConnection.value == GimbalConnectionState.Connecting) {
            // Abort an in-flight handshake instead of queueing behind it: the
            // transport cancel unblocks the suspended connect().
            runCatching { transport.disconnect() }
            mutableConnection.value = GimbalConnectionState.Disconnected
        }
        commandMutex.withLock {
            stopPoller()
            if (mutableConnection.value != GimbalConnectionState.Disconnected) {
                runCatching { transport.disconnect() }
                // The firmware fail-stops both axes when BLE drops.
                setMotion(GimbalMotionState.Holding)
            }
            mutableConnection.value = GimbalConnectionState.Disconnected
        }
    }

    private suspend fun discoverMotorsAndAxes(): F32cGimbalStatus? {
        val scanResult = CompletableDeferred<F32cScanResult>()
        pendingScanResult = scanResult
        val gimbalStatus = CompletableDeferred<F32cGimbalStatus>()
        pendingGimbalStatus = gimbalStatus
        try {
            transport.writeCommand("""{"cmd":"scan"}""")
            val scanned = withTimeoutOrNull(SCAN_RESULT_TIMEOUT_MS) { scanResult.await() }
            if (scanned == null || !scanned.ok || scanned.motors.isEmpty()) {
                reject(GimbalCommandError.DeviceNotFound)
            }
            // The firmware pushes gimbal_state with the configured axis ids right
            // after a scan. Without that push the axis roles are unknown, and the
            // axis ids must also match motors this scan actually found.
            val status = withTimeoutOrNull(GIMBAL_STATUS_TIMEOUT_MS) { gimbalStatus.await() } ?: return null
            val scannedIds = scanned.motors.map { it.id }.toSet()
            return if (status.panId in scannedIds && status.tiltId in scannedIds) status else null
        } finally {
            pendingScanResult = null
            pendingGimbalStatus = null
        }
    }

    /** Waits for one measured total_angle per axis; rejects the connection without both. */
    private suspend fun awaitMeasuredBaseline() {
        val pendingAngles = mutableMapOf(axisPan to CompletableDeferred<Double>(), axisTilt to CompletableDeferred<Double>())
        pendingBaselineAngles = pendingAngles
        try {
            for (addr in listOf(axisPan, axisTilt)) {
                transport.writeCommand("""{"cmd":"query","addr":$addr,"type":"total_angle"}""")
                delay(QUERY_SPACING_MS)
            }
            val pan = withTimeoutOrNull(BASELINE_TIMEOUT_MS) { pendingAngles.getValue(axisPan).await() }
            val tilt = withTimeoutOrNull(BASELINE_TIMEOUT_MS) { pendingAngles.getValue(axisTilt).await() }
            if (pan == null || tilt == null) {
                mutableTelemetry.value = mutableTelemetry.value.copy(fault = "无实测位置基线，拒绝就绪")
                reject(GimbalCommandError.DeviceNotFound)
            }
            if (pan !in capability.panRangeDeg) {
                mutableTelemetry.value = mutableTelemetry.value.copy(
                    panDeg = pan,
                    fault = "实测水平位置超出安全范围，拒绝就绪",
                )
                reject(GimbalCommandError.PanOutOfRange)
            }
            if (tilt !in capability.tiltRangeDeg!!) {
                mutableTelemetry.value = mutableTelemetry.value.copy(
                    tiltDeg = tilt,
                    fault = "实测垂直位置超出 ±90°，拒绝就绪",
                )
                reject(GimbalCommandError.TiltOutOfRange)
            }
            mutableTelemetry.value = mutableTelemetry.value.copy(
                panDeg = pan,
                tiltDeg = tilt,
                measuredAtMs = clock.nowUs() / 1_000,
            )
        } finally {
            pendingBaselineAngles = null
        }
    }

    /**
     * Writes one command and waits a bounded time for its cmd_result. A firmware
     * rejection maps to a link rejection after a defensive axis stop (the wire has
     * no command id, so a misattributed result must fail toward "stopped"); a
     * missing cmd_result is downgraded to transport acceptance because the write
     * itself was accepted.
     */
    private suspend fun writeAwaitingResult(json: String) {
        val result = CompletableDeferred<Pair<Boolean, String>>()
        pendingCommandResult = result
        awaitingCommandResult = true
        try {
            transport.writeCommand(json)
            val (ok, msg) = withTimeoutOrNull(CMD_RESULT_TIMEOUT_MS) { result.await() }
                ?: return // no answer in time; the write itself was transport-accepted
            if (!ok) {
                runCatching { stopBothAxes() }
                reject(rejectionFor(msg))
            }
        } finally {
            awaitingCommandResult = false
            pendingCommandResult = null
        }
    }

    /** Stop both axes with the firmware's queue-bypassing stop (P1-2); keeps torque. */
    private suspend fun stopBothAxes() {
        transport.writeCommand("""{"cmd":"jog","axis":"pan","dir":0}""")
        transport.writeCommand("""{"cmd":"jog","axis":"tilt","dir":0}""")
    }

    private fun rejectionFor(message: String): GimbalCommandError = when {
        message.contains("限位") && message.contains("tilt") -> GimbalCommandError.TiltOutOfRange
        message.contains("限位") && message.contains("pan") -> GimbalCommandError.PanOutOfRange
        else -> {
            mutableTelemetry.value = mutableTelemetry.value.copy(fault = message)
            GimbalCommandError.NotReady
        }
    }

    private fun dispatch(payload: String) {
        F32cResponses.gimbalStatus(payload)?.let { status ->
            pendingGimbalStatus?.complete(status)
            if (mutableConnection.value == GimbalConnectionState.Ready) {
                axisPan = status.panId
                axisTilt = status.tiltId
            }
            return
        }
        F32cResponses.scanResult(payload)?.let { scan ->
            pendingScanResult?.complete(scan)
            return
        }
        F32cResponses.commandResult(payload)?.let { (ok, msg) ->
            // A failed bus query also answers with cmd_result ("查询失败…", observed
            // on hardware); it belongs to the telemetry poller and must never reject
            // an in-flight control command whose move still executes.
            if (msg.startsWith(QUERY_FAILURE_PREFIX)) return
            pendingCommandResult?.complete(ok to msg)
            return
        }
        F32cResponses.queryResult(payload)?.let { query ->
            when (query.type) {
                "total_angle" -> query.value?.let { angle ->
                    pendingBaselineAngles?.get(query.addr)?.complete(angle)
                    onMeasuredAngle(query.addr, angle)
                }
                "speed" -> query.value?.let { rpm ->
                    axisSpeedRpm = axisSpeedRpm + (query.addr to rpm)
                    maybeMotionSettled()
                }
            }
        }
    }

    private fun onMeasuredAngle(addr: Int, angleDeg: Double) {
        val current = mutableTelemetry.value
        val updated = when (addr) {
            axisPan -> current.copy(panDeg = angleDeg, measuredAtMs = clock.nowUs() / 1_000)
            axisTilt -> current.copy(tiltDeg = angleDeg, measuredAtMs = clock.nowUs() / 1_000)
            else -> return
        }
        mutableTelemetry.value = updated
        if (addr == axisTilt && angleDeg !in capability.tiltRangeDeg!!) {
            mutableTelemetry.value = updated.copy(fault = "实测垂直位置超出 ±90°，已请求停机")
            setMotion(GimbalMotionState.Fault)
            scope.launch { runCatching { stopBothAxes() } }
            return
        }
        maybeMotionSettled()
    }

    private fun maybeMotionSettled() {
        if (mutableMotion.value != GimbalMotionState.Moving) return
        val panSpeed = axisSpeedRpm[axisPan] ?: return
        val tiltSpeed = axisSpeedRpm[axisTilt] ?: return
        if (kotlin.math.abs(panSpeed) < SPEED_EPSILON_RPM && kotlin.math.abs(tiltSpeed) < SPEED_EPSILON_RPM) {
            setMotion(GimbalMotionState.Idle)
            // Landing: pull a fresh angle burst right away so the displayed position
            // catches up with the physical arrival instead of waiting a full cycle.
            refreshTelemetry()
        }
    }

    private fun refreshTelemetry() {
        scope.launch { pollTelemetryOnce() }
    }

    private fun startPoller() {
        stopPoller()
        pollerJob = scope.launch {
            while (mutableConnection.value == GimbalConnectionState.Ready) {
                runCatching { pollTelemetryOnce() }
                // While a command is executing, poll fast so arrival shows within a
                // few hundred milliseconds; idle telemetry runs at the slow cadence.
                delay(
                    if (mutableMotion.value == GimbalMotionState.Moving) MOTION_POLL_INTERVAL_MS
                    else TELEMETRY_POLL_INTERVAL_MS,
                )
            }
        }
    }

    private fun stopPoller() {
        pollerJob?.cancel()
        pollerJob = null
    }

    private suspend fun pollTelemetryOnce() = pollMutex.withLock {
        if (mutableConnection.value != GimbalConnectionState.Ready || awaitingCommandResult) return@withLock
        // Speeds first so settle detection leads; the same burst's trailing angle
        // reads then carry the near-final position without another wait.
        for (addr in listOf(axisPan, axisTilt)) {
            transport.writeCommand("""{"cmd":"query","addr":$addr,"type":"speed"}""")
            delay(QUERY_SPACING_MS)
        }
        for (addr in listOf(axisPan, axisTilt)) {
            if (awaitingCommandResult) return@withLock
            transport.writeCommand("""{"cmd":"query","addr":$addr,"type":"total_angle"}""")
            delay(QUERY_SPACING_MS)
        }
    }

    private fun requireReady() {
        if (mutableConnection.value != GimbalConnectionState.Ready) reject(GimbalCommandError.NotReady)
        if (estopLatched) reject(GimbalCommandError.NotReady)
    }

    /** Enters Moving with pre-command speed readings discarded for settle detection. */
    private fun enterMoving() {
        axisSpeedRpm = emptyMap()
        setMotion(GimbalMotionState.Moving)
    }

    private fun reject(reason: GimbalCommandError): Nothing {
        eventBus.tryEmit(GimbalEvent.CommandRejected(reason))
        throw GimbalCommandException(reason)
    }

    private fun setMotion(state: GimbalMotionState) {
        mutableMotion.value = state
        eventBus.tryEmit(GimbalEvent.MotionChanged(state))
    }

    private fun nextControlSeq(): UShort {
        controlSeq = (controlSeq.toInt() + 1).toUShort()
        return controlSeq
    }

    private var controlSeq: UShort = 0u

    private companion object {
        /** Firmware protocol limits: pan covers the full single-turn range, tilt ±90°. */
        val capability = GimbalCapability(
            panRangeDeg = -180.0..180.0,
            tiltRangeDeg = -90.0..90.0,
            maxDegreesPerSecond = 30.0,
            supportedModes = setOf(GimbalMode.Manual, GimbalMode.Hold, GimbalMode.Home),
            watchdogMs = 400,
            firmwareVersion = "f32c-unknown",
            protocolVersion = "f32c-ble-bridge-1",
        )

        const val DEFAULT_PAN_ID = 1
        const val DEFAULT_TILT_ID = 2
        const val SCAN_RESULT_TIMEOUT_MS = 10_000L
        const val GIMBAL_STATUS_TIMEOUT_MS = 2_000L
        const val BASELINE_TIMEOUT_MS = 2_000L
        const val CMD_RESULT_TIMEOUT_MS = 250L
        const val TELEMETRY_POLL_INTERVAL_MS = 750L
        const val MOTION_POLL_INTERVAL_MS = 200L
        const val QUERY_SPACING_MS = 40L
        const val SPEED_EPSILON_RPM = 0.01
        const val QUERY_FAILURE_PREFIX = "查询失败"
        const val MAX_SPEED_RPM = 300
        const val VELOCITY_WATCHDOG_MS = 800L
        const val VELOCITY_WATCHDOG_INTERVAL_MS = 200L
    }
}
