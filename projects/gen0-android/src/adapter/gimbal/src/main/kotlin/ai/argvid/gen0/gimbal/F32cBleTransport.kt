package ai.argvid.gen0.gimbal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.cancel

data class F32cBleDevice(
    val id: String,
    val name: String,
)

data class F32cBleNotification(
    val characteristic: UUID,
    val payload: ByteArray,
)

data class F32cWriteReceipt(
    /** True only when Android's GATT write callback reports success. */
    val transportAccepted: Boolean,
)

private const val DEFAULT_SCAN_TIMEOUT_MS = 5_000L
private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
private const val WRITE_TIMEOUT_MS = 3_000L

interface F32cBleTransport {
    val notifications: SharedFlow<F32cBleNotification>

    /** Emits once when an established connection drops without an explicit [disconnect]. */
    val connectionLost: SharedFlow<Unit>

    suspend fun scan(timeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS): List<F32cBleDevice>
    suspend fun connect(id: String, timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS)
    suspend fun writeCommand(json: String): F32cWriteReceipt
    suspend fun disconnect()
}

/**
 * Android BluetoothGatt transport for the project-local F32C protocol.
 *
 * This class deliberately stops at GATT transport semantics. A successful write is
 * not a motor acknowledgement and is never exposed as device-confirmed motion.
 */
// BLUETOOTH_SCAN/BLUETOOTH_CONNECT are enforced before this transport is reached:
// feature:session's PermissionCoordinator gates the real-BLE mode, and the device
// instrumentation test skips unless both are granted.
@SuppressLint("MissingPermission")
class AndroidF32cBleTransport(
    context: Context,
    private val callbackDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) : F32cBleTransport {
    private val appContext = context.applicationContext

    // Acquired lazily so constructing this transport never fails app startup on a
    // device without a Bluetooth stack; scan/connect surface the error at first use.
    private val bluetoothManager by lazy {
        appContext.getSystemService(BluetoothManager::class.java) ?: error("BluetoothManager is unavailable")
    }
    private val adapter: BluetoothAdapter
        get() = bluetoothManager.adapter ?: error("Bluetooth is unavailable")
    private val scanLock = Mutex()
    private val writeLock = Mutex()
    private val notificationsBus = MutableSharedFlow<F32cBleNotification>(extraBufferCapacity = 32)
    private val connectionLostBus = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val transportScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + callbackDispatcher)
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var negotiatedMtu: Int = DEFAULT_ATT_MTU
    private var pendingWrite: kotlinx.coroutines.CancellableContinuation<Int>? = null
    private var pendingDescriptor: kotlinx.coroutines.CancellableContinuation<Unit>? = null
    private var descriptorQueue: ArrayDeque<BluetoothGattDescriptor> = ArrayDeque()
    @Volatile private var connectionDeferred: CompletableDeferred<Unit>? = null
    private var servicesDiscoveryStarted = false
    private var closed = AtomicBoolean(false)
    private val writeTimedOut = AtomicBoolean(false)

    override val notifications: SharedFlow<F32cBleNotification> = notificationsBus.asSharedFlow()
    override val connectionLost: SharedFlow<Unit> = connectionLostBus.asSharedFlow()

    override suspend fun scan(timeoutMs: Long): List<F32cBleDevice> = scanLock.withLock {
        require(timeoutMs > 0)
        check(!closed.get()) { "transport is closed" }
        val bleScanner = adapter.bluetoothLeScanner ?: error("BLE scanner is unavailable")
        scanner = bleScanner
        // ScanCallback fires on binder threads while the caller thread reads the map.
        val devices = java.util.Collections.synchronizedMap(linkedMapOf<String, F32cBleDevice>())
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull() ?: return
                if (F32cBleContract.isAdvertisedNameAccepted(name)) {
                    devices[device.address] = F32cBleDevice(device.address, name)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                // The suspended caller receives an empty result; Android has no portable
                // exception type for scan failure that is useful to the domain layer.
            }
        }
        bleScanner.startScan(callback)
        try {
            delay(timeoutMs)
            devices.values.toList()
        } finally {
            runCatching { bleScanner.stopScan(callback) }
            scanner = null
        }
    }

    override suspend fun connect(id: String, timeoutMs: Long) {
        require(timeoutMs > 0)
        check(!closed.get()) { "transport is closed" }
        disconnect()
        writeTimedOut.set(false)
        val device = adapter.getRemoteDevice(id)
        val ready = CompletableDeferred<Unit>()
        connectionDeferred = ready
        val callback = callbackFor(ready)
        gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(timeoutMs) { ready.await() }
        } catch (error: Throwable) {
            disconnect()
            throw error
        } finally {
            connectionDeferred = null
        }
    }

    override suspend fun writeCommand(json: String): F32cWriteReceipt = writeLock.withLock {
        check(!closed.get()) { "transport is closed" }
        check(!writeTimedOut.get()) {
            "FF03 write previously timed out; disconnect and reconnect before writing again"
        }
        val activeGatt = gatt ?: error("GATT is not connected")
        val characteristic = commandCharacteristic ?: error("FF03 is unavailable")
        val payload = F32cBleContract.commandJson(json)
        require(payload.size <= negotiatedMtu - GATT_HEADER_BYTES) {
            "F32C command of ${payload.size} bytes exceeds the negotiated ATT payload ${negotiatedMtu - GATT_HEADER_BYTES}"
        }
        val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Int> { continuation ->
                pendingWrite = continuation
                continuation.invokeOnCancellation { if (pendingWrite === continuation) pendingWrite = null }
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    // BluetoothStatusCodes.SUCCESS and GATT_SUCCESS share the value 0.
                    activeGatt.writeCharacteristic(
                        characteristic,
                        payload,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.setValue(payload)
                    @Suppress("DEPRECATION")
                    activeGatt.writeCharacteristic(characteristic)
                }
                if (!started && continuation.isActive) {
                    pendingWrite = null
                    continuation.resume(BluetoothGatt.GATT_FAILURE)
                }
            }
        } ?: run {
            // A callback that never arrives would otherwise wedge writeLock forever and
            // block every later write, including emergency stops. Fail fast from now on
            // until the caller disconnects and reconnects.
            writeTimedOut.set(true)
            pendingWrite = null
            throw IllegalStateException("FF03 write timed out after ${WRITE_TIMEOUT_MS}ms without a GATT callback")
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            throw IllegalStateException("FF03 write failed with status $status")
        }
        F32cWriteReceipt(transportAccepted = true)
    }

    override suspend fun disconnect() {
        val activeGatt = gatt ?: return
        gatt = null
        commandCharacteristic = null
        servicesDiscoveryStarted = false
        negotiatedMtu = DEFAULT_ATT_MTU
        pendingWrite?.cancel(CancellationException("GATT disconnected"))
        pendingWrite = null
        pendingDescriptor?.cancel(CancellationException("GATT disconnected"))
        pendingDescriptor = null
        descriptorQueue.clear()
        connectionDeferred?.cancel(CancellationException("GATT disconnected"))
        connectionDeferred = null
        runCatching { activeGatt.disconnect() }
        runCatching { activeGatt.close() }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            val activeGatt = gatt
            gatt = null
            commandCharacteristic = null
            servicesDiscoveryStarted = false
            pendingWrite?.cancel(CancellationException("GATT transport closed"))
            pendingWrite = null
            pendingDescriptor?.cancel(CancellationException("GATT transport closed"))
            pendingDescriptor = null
            descriptorQueue.clear()
            connectionDeferred?.cancel(CancellationException("GATT transport closed"))
            connectionDeferred = null
            runCatching { activeGatt?.disconnect() }
            runCatching { activeGatt?.close() }
            transportScope.cancel()
        }
    }

    private fun callbackFor(ready: CompletableDeferred<Unit>) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothGatt.STATE_CONNECTED) {
                pendingWrite?.let {
                    pendingWrite = null
                    if (it.isActive) it.resume(BluetoothGatt.GATT_FAILURE)
                }
                pendingDescriptor?.let {
                    pendingDescriptor = null
                    if (it.isActive) it.resumeWithException(CancellationException("GATT disconnected"))
                }
                if (ready.isActive) ready.completeExceptionally(IllegalStateException("GATT connection failed: $status"))
                if (newState == BluetoothGatt.STATE_DISCONNECTED && this@AndroidF32cBleTransport.gatt === gatt) {
                    // An explicit disconnect() nulls `gatt` before the callback fires, so
                    // reaching here with no pending handshake means an established
                    // connection was lost on its own.
                    this@AndroidF32cBleTransport.gatt = null
                    commandCharacteristic = null
                    servicesDiscoveryStarted = false
                    if (connectionDeferred == null) connectionLostBus.tryEmit(Unit)
                    runCatching { gatt.close() }
                }
                return
            }
            servicesDiscoveryStarted = false
            if (!gatt.requestMtu(F32cBleContract.requestedMtu)) {
                discoverServicesOnce(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
            // On failure the previous MTU stands; writeCommand rejects payloads that
            // no longer fit instead of letting the stack fail the write opaquely.
            discoverServicesOnce(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ready.completeExceptionally(IllegalStateException("GATT service discovery failed: $status"))
                return
            }
            val service = gatt.getService(F32cBleContract.serviceUuid)
            val command = service?.getCharacteristic(F32cBleContract.commandUuid)
            val statusChar = service?.getCharacteristic(F32cBleContract.statusUuid)
            val responseChar = service?.getCharacteristic(F32cBleContract.responseUuid)
            if (service == null || command == null || statusChar == null || responseChar == null) {
                ready.completeExceptionally(IllegalStateException("F32C service or characteristic is missing"))
                return
            }
            commandCharacteristic = command
            descriptorQueue = ArrayDeque(
                listOfNotNull(
                    statusChar.getDescriptor(CCCD_UUID),
                    responseChar.getDescriptor(CCCD_UUID),
                ),
            )
            if (descriptorQueue.isEmpty()) {
                ready.completeExceptionally(IllegalStateException("F32C notify descriptors are missing"))
                return
            }
            enableNextNotification(gatt, ready, statusChar, responseChar)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val continuation = pendingDescriptor ?: return
            pendingDescriptor = null
            if (status == BluetoothGatt.GATT_SUCCESS) continuation.resume(Unit)
            else continuation.resumeWithException(IllegalStateException("CCCD write failed: $status"))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.let {
                pendingWrite = null
                if (it.isActive) it.resume(status)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            emitNotification(characteristic.uuid, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            emitNotification(characteristic.uuid, value)
        }
    }

    private fun discoverServicesOnce(gatt: BluetoothGatt) {
        if (servicesDiscoveryStarted) return
        servicesDiscoveryStarted = true
        if (!gatt.discoverServices()) {
            connectionDeferred?.completeExceptionally(
                IllegalStateException("GATT service discovery could not be started"),
            )
        }
    }

    private fun enableNextNotification(
        gatt: BluetoothGatt,
        ready: CompletableDeferred<Unit>,
        statusChar: BluetoothGattCharacteristic,
        responseChar: BluetoothGattCharacteristic,
    ) {
        val descriptor = descriptorQueue.removeFirstOrNull()
        if (descriptor == null) {
            ready.complete(Unit)
            return
        }
        val characteristic = when (descriptor.characteristic.uuid) {
            F32cBleContract.statusUuid -> statusChar
            F32cBleContract.responseUuid -> responseChar
            else -> null
        }
        if (characteristic == null || !gatt.setCharacteristicNotification(characteristic, true)) {
            ready.completeExceptionally(IllegalStateException("Unable to enable F32C notification"))
            return
        }
        descriptor.value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        transportScope.launch {
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    pendingDescriptor = continuation
                    continuation.invokeOnCancellation { if (pendingDescriptor === continuation) pendingDescriptor = null }
                    @Suppress("DEPRECATION")
                    if (!gatt.writeDescriptor(descriptor)) {
                        pendingDescriptor = null
                        continuation.resumeWithException(IllegalStateException("Unable to write CCCD"))
                    }
                }
                enableNextNotification(gatt, ready, statusChar, responseChar)
            } catch (error: Throwable) {
                ready.completeExceptionally(error)
            }
        }
    }

    private fun emitNotification(uuid: UUID, payload: ByteArray) {
        notificationsBus.tryEmit(F32cBleNotification(uuid, payload.copyOf()))
    }

    private companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val GATT_HEADER_BYTES = 3
        const val DEFAULT_ATT_MTU = 23
    }
}
