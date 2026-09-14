package com.vrremote.controller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** One row in the device list. */
data class DeviceEntry(
    val name: String,
    val address: String,
    val rssi: Int,
    val bonded: Boolean,
    /** True only for the controller; every other device is listed for context. */
    val isController: Boolean,
)

/** The single state the UI is allowed to show. */
sealed class ConnectionState(val label: String) {
    object Idle : ConnectionState("空闲")
    object Scanning : ConnectionState("扫描中")
    class Connecting(name: String) : ConnectionState("连接中 · $name")
    class Discovering(name: String) : ConnectionState("发现服务 · $name")
    class Subscribing(name: String) : ConnectionState("订阅通知 · $name")
    class Ready(val name: String, val address: String) : ConnectionState("已就绪 · $name")
    class Disconnected(reason: String) : ConnectionState("已断开 · $reason")
    class Failed(message: String) : ConnectionState("错误 · $message")
}

/**
 * Raw BluetoothGatt transport for the Huawei CF20 controller.
 *
 * The controller exposes a vendor service only, so it is invisible to every
 * system profile: an app has to hold the GATT client itself.
 *
 * The controller never completes system bonding (every createBond attempt ends
 * in bt_status:auth_failure in the Bluetooth stack) and its vendor service is
 * readable without encryption, so this class connects straight to GATT and
 * never calls createBond().
 */
@SuppressLint("MissingPermission")
class BleControllerTransport(private val context: Context) {

    companion object {
        private const val TAG = "VrRemoteBle"

        val SERVICE_UUID: UUID = UUID.fromString("622f5b2a-60da-4484-929a-69e3a9140258")
        /**
         * The channel that actually carries the 20 byte input report.
         *
         * Huawei's client enables 233827E5 first and this one half a second
         * later, which makes it look like both are needed. Measured on the real
         * controller: subscribing to this characteristic alone streams input at
         * ~100 packets per second, and 233827E5 delivers nothing.
         */
        val INPUT_UUID: UUID = UUID.fromString("ffb4a651-c224-420b-a134-13d3aba313fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)
        private const val MAX_RETRIES = 5

        /** A connection attempt that never reports back is abandoned after this. */
        private const val CONNECT_TIMEOUT_MS = 12_000L

        /** Enough notifications to prove a channel is live without flooding logcat. */
        private const val LOGGED_NOTIFICATIONS_PER_UUID = 3
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _packets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    val packets: SharedFlow<ByteArray> = _packets.asSharedFlow()

    private val _devices = MutableStateFlow<List<DeviceEntry>>(emptyList())
    val devices: StateFlow<List<DeviceEntry>> = _devices.asStateFlow()

    private val known = LinkedHashMap<String, DeviceEntry>()
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var retries = 0
    private var connectTimeout: Runnable? = null

    private val notifyLogCount = HashMap<UUID, Int>()

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        refreshDevices()
    }

    fun stop() {
        stopScan()
        handler.removeCallbacksAndMessages(null)
        closeGatt()
    }

    // -------------------------------------------------------------- permissions

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    // ----------------------------------------------------------------- scanning

    fun startScan() {
        if (!hasPermissions()) {
            _state.value = ConnectionState.Failed("缺少蓝牙权限")
            return
        }
        val adapter = adapter ?: run {
            _state.value = ConnectionState.Failed("本机没有蓝牙适配器")
            return
        }
        if (!adapter.isEnabled) {
            _state.value = ConnectionState.Failed("蓝牙未开启")
            return
        }
        if (scanning) return
        val scanner = adapter.bluetoothLeScanner ?: run {
            _state.value = ConnectionState.Failed("蓝牙扫描器不可用")
            return
        }
        // An already bonded controller needs no scan at all: connect directly.
        try {
            adapter.bondedDevices
                ?.firstOrNull { it.name?.startsWith("HUAWEI CF20") == true }
                ?.let {
                    Log.i(TAG, "bonded controller ${it.address} exists, connecting without scan")
                    connect(it)
                    return
                }
        } catch (error: SecurityException) {
            Log.w(TAG, "cannot read bonded devices", error)
        }
        scanning = true
        _state.value = ConnectionState.Scanning
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (ignored: Exception) {
        }
        if (_state.value is ConnectionState.Scanning) {
            _state.value = ConnectionState.Idle
        }
    }

    fun isScanning(): Boolean = scanning

    fun refreshDevices() {
        try {
            adapter?.bondedDevices?.forEach { upsert(it, 0) }
        } catch (error: SecurityException) {
            Log.w(TAG, "cannot read bonded devices", error)
        }
        publishDevices()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _state.value = ConnectionState.Failed("扫描失败，错误码 $errorCode")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        // Keep the list useful: skip anonymous beacons that carry neither a
        // name nor our service UUID. They are the bulk of scan traffic and
        // only exist to churn the UI.
        val hasName = try {
            result.device.name != null
        } catch (error: SecurityException) {
            false
        }
        if (hasName || isTargetController(result)) {
            upsert(result.device, result.rssi, fromScan = true)
        }
        maybeAutoConnect(result)
    }

    private fun isTargetController(result: ScanResult): Boolean {
        val advertisesService = result.scanRecord?.serviceUuids
            ?.any { it.uuid == SERVICE_UUID } == true
        return advertisesService || isControllerName(deviceName(result.device))
    }

    /** The controller family always advertises this name prefix. */
    private fun isControllerName(name: String): Boolean = name.startsWith("HUAWEI CF20")

    /** The controller is the only device we ever want; pair/connect as soon as it shows up. */
    private fun maybeAutoConnect(result: ScanResult) {
        if (!scanning) return
        if (_state.value !is ConnectionState.Scanning) return
        if (gatt != null) return
        if (!isTargetController(result)) return
        Log.i(TAG, "target controller found, auto-connecting to ${result.device.address}")
        connect(result.device)
    }

    private fun upsert(device: BluetoothDevice, rssi: Int, fromScan: Boolean = false) {
        val entry = DeviceEntry(
            name = deviceName(device),
            address = device.address,
            rssi = rssi,
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            isController = isControllerName(deviceName(device)),
        )
        known[entry.address] = entry
        if (fromScan) schedulePublish() else publishDevices()
    }

    // Scan callbacks fire dozens of times per second. Publishing the whole
    // list on every callback makes the UI rebuild the rows nonstop, so scan
    // results are merged and published at most a few times per second.
    private var publishScheduled = false

    private fun schedulePublish() {
        if (publishScheduled) return
        publishScheduled = true
        handler.postDelayed({
            publishScheduled = false
            publishDevices()
        }, 400)
    }

    private fun publishDevices() {
        _devices.value = known.values.sortedWith(
            compareByDescending<DeviceEntry> { it.bonded }.thenByDescending { it.rssi }
        )
    }

    private fun deviceName(device: BluetoothDevice): String =
        try {
            device.name ?: "(未命名)"
        } catch (error: SecurityException) {
            "(无权限读取名称)"
        }

    // ---------------------------------------------------------------- connecting

    /**
     * Connects to a picked device.
     *
     * Only the controller is accepted: tapping any other row used to tear down
     * a working link and then sit on "connecting" forever.
     */
    fun connect(entry: DeviceEntry) {
        if (!entry.isController) {
            _state.value = ConnectionState.Failed("${entry.name} 不是手柄，没有连接")
            return
        }
        val device = try {
            adapter?.getRemoteDevice(entry.address)
        } catch (error: IllegalArgumentException) {
            null
        }
        if (device == null) {
            _state.value = ConnectionState.Failed("找不到设备 ${entry.address}")
            return
        }
        connect(device)
    }

    private fun connect(device: BluetoothDevice) {
        retries = 0
        openGatt(device)
    }

    private fun openGatt(device: BluetoothDevice) {
        stopScan()
        closeGatt()
        _state.value = ConnectionState.Connecting(deviceName(device))
        gatt = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (error: SecurityException) {
            null
        }
        if (gatt == null) {
            _state.value = ConnectionState.Failed("connectGatt 返回空")
            return
        }
        scheduleConnectTimeout(device)
    }

    /**
     * connectGatt has no built-in timeout: an unreachable device leaves the app
     * waiting forever, so the attempt is abandoned and reported instead.
     */
    private fun scheduleConnectTimeout(device: BluetoothDevice) {
        cancelConnectTimeout()
        val runnable = Runnable {
            if (_state.value is ConnectionState.Connecting) {
                closeGatt()
                _state.value = ConnectionState.Failed("连接 ${deviceName(device)} 超时")
            }
        }
        connectTimeout = runnable
        handler.postDelayed(runnable, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout() {
        connectTimeout?.let { handler.removeCallbacks(it) }
        connectTimeout = null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            cancelConnectTimeout()
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Stop scanning here as well: the controller stays
                    // connectable while it is connected, so a live scan would
                    // otherwise keep reporting it and the UI would look stuck
                    // on "scanning" even though we are connected.
                    stopScan()
                    retries = 0
                    _state.value = ConnectionState.Discovering(deviceName(callbackGatt.device))
                    callbackGatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val device = callbackGatt.device
                    closeGatt()
                    scheduleReconnect(device, status)
                }
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = ConnectionState.Failed("服务发现失败，状态 $status")
                return
            }
            val name = deviceName(callbackGatt.device)
            val service = callbackGatt.getService(SERVICE_UUID)
            if (service == null) {
                _state.value = ConnectionState.Failed("设备上没有目标服务 $SERVICE_UUID")
                return
            }
            val characteristic = service.getCharacteristic(INPUT_UUID)
            if (characteristic == null) {
                _state.value = ConnectionState.Failed("目标服务里没有输入特征值 $INPUT_UUID")
                return
            }
            if (!callbackGatt.setCharacteristicNotification(characteristic, true)) {
                _state.value = ConnectionState.Failed("无法开启本地通知")
                return
            }
            val cccd = characteristic.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                _state.value = ConnectionState.Failed("输入特征值缺少 CCCD 描述符")
                return
            }
            _state.value = ConnectionState.Subscribing(name)
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                callbackGatt.writeDescriptor(cccd, enable) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = enable
                    callbackGatt.writeDescriptor(cccd)
                }
            }
            if (!accepted) {
                _state.value = ConnectionState.Failed("写入 CCCD 被拒绝")
            }
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = ConnectionState.Failed("CCCD 写入返回 $status")
                return
            }
            _state.value = ConnectionState.Ready(
                deviceName(callbackGatt.device),
                callbackGatt.device.address,
            )
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            deliver(characteristic, value)
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            deliver(characteristic, value)
        }
    }

    private fun deliver(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != INPUT_UUID) return
        val logged = notifyLogCount[characteristic.uuid] ?: 0
        if (logged < LOGGED_NOTIFICATIONS_PER_UUID) {
            notifyLogCount[characteristic.uuid] = logged + 1
            Log.i(TAG, "notify ${characteristic.uuid} ${value.size} bytes")
        }
        _packets.tryEmit(value.copyOf())
    }

    private fun scheduleReconnect(device: BluetoothDevice, status: Int) {
        val name = deviceName(device)
        if (retries >= MAX_RETRIES) {
            _state.value = ConnectionState.Disconnected("$name 重试 $MAX_RETRIES 次后放弃（状态 $status）")
            return
        }
        val delay = RETRY_DELAYS_MS[minOf(retries, RETRY_DELAYS_MS.size - 1)]
        retries++
        _state.value = ConnectionState.Disconnected(
            "$name 已断开，第 $retries 次重连将在 ${delay / 1000}s 后（状态 $status）"
        )
        handler.postDelayed({ openGatt(device) }, delay)
    }

    fun closeGatt() {
        cancelConnectTimeout()
        val open = gatt ?: return
        gatt = null
        try {
            open.disconnect()
        } catch (ignored: Exception) {
        }
        try {
            open.close()
        } catch (ignored: Exception) {
        }
    }
}
