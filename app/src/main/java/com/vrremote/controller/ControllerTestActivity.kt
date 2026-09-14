package com.vrremote.controller

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vrremote.controller.protocol.ControllerButton
import com.vrremote.controller.protocol.ControllerFrame
import com.vrremote.controller.protocol.ControllerFrameParser
import com.vrremote.controller.protocol.ParsedPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Diagnostic page: shows every decoded field of every incoming report.
 *
 * This is the validation screen that proved the protocol, kept as the "did my
 * controller work" page of the finished app.
 */
class ControllerTestActivity : Activity() {

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        const val LIVE_INTERVAL_MS = 50L
        const val LOG_INTERVAL_MS = 200L
        const val LOG_LINES = 200
        const val TRAIL_POINTS = 50
        const val STATUS_DURATION_MS = 2_000L

        val COLOR_TEXT = 0xFFE6EDF3.toInt()
        val COLOR_KEY_OFF = 0xFF2B333D.toInt()
        val COLOR_KEY_ON = 0xFFD32F2F.toInt()
        val COLOR_OK = 0xFF4CAF50.toInt()
        val COLOR_WARN = 0xFFFFB300.toInt()
        val COLOR_BAD = 0xFFEF5350.toInt()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var stateText: TextView
    private lateinit var deviceText: TextView
    private lateinit var statsText: TextView
    private lateinit var scanButton: Button
    private lateinit var deviceList: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var touchpadView: TouchpadView
    private lateinit var touchpadText: TextView
    private lateinit var motionView: MotionView
    private lateinit var motionText: TextView
    private lateinit var rawLog: TextView
    private lateinit var rawScroll: ScrollView

    private val keyViews = LinkedHashMap<ControllerButton, TextView>()

    private val lock = Any()
    private var latestFrame: ControllerFrame? = null
    private var previousPressed: Set<ControllerButton> = emptySet()
    private val pressCounts = HashMap<ControllerButton, Int>()
    private val trail = ArrayDeque<Pair<Int, Int>>()
    private val rawLines = ArrayDeque<String>()
    private var totalPackets = 0

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var lastDeviceSignature = ""
    private var currentState: ConnectionState = ConnectionState.Idle
    private var lastRateAt = 0L
    private var lastRateCount = 0
    private var packetsPerSecond = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controller_test)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        RemoteCore.init(this)
        bindViews()

        scanButton.setOnClickListener {
            if (RemoteCore.isScanning()) RemoteCore.stopScan() else RemoteCore.startScan()
        }
        findViewById<Button>(R.id.clearButton).setOnClickListener { clearLogs() }
        findViewById<Button>(R.id.copyButton).setOnClickListener { copyLogs() }

        observeTransport()
        startTicks()

        if (!hasBluetoothPermissions()) {
            requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS)
        }
    }

    private fun bindViews() {
        stateText = findViewById(R.id.stateText)
        deviceText = findViewById(R.id.deviceText)
        statsText = findViewById(R.id.statsText)
        scanButton = findViewById(R.id.scanButton)
        deviceList = findViewById(R.id.deviceList)
        statusText = findViewById(R.id.statusText)
        touchpadView = findViewById(R.id.touchpadView)
        touchpadText = findViewById(R.id.touchpadText)
        motionView = findViewById(R.id.motionView)
        motionText = findViewById(R.id.motionText)
        rawLog = findViewById(R.id.rawLog)
        rawScroll = findViewById(R.id.rawScroll)

        keyViews[ControllerButton.TRIGGER] = findViewById(R.id.keyTrigger)
        keyViews[ControllerButton.CONFIRM] = findViewById(R.id.keyConfirm)
        keyViews[ControllerButton.VOLUME_DOWN] = findViewById(R.id.keyVolumeDown)
        keyViews[ControllerButton.VOLUME_UP] = findViewById(R.id.keyVolumeUp)
        keyViews[ControllerButton.HOME] = findViewById(R.id.keyHome)
        keyViews[ControllerButton.BACK] = findViewById(R.id.keyBack)

        for ((button, view) in keyViews) {
            view.text = "${button.label}\n0"
        }
    }

    private fun observeTransport() {
        scope.launch {
            RemoteCore.connectionState.collect { state ->
                renderState(state)
                renderDevices(RemoteCore.devices.value)
            }
        }
        scope.launch {
            RemoteCore.devices.collect { devices -> renderDevices(devices) }
        }
        scope.launch(Dispatchers.Default) {
            RemoteCore.packets.collect { bytes -> ingest(bytes) }
        }
    }

    private fun ingest(bytes: ByteArray) {
        val parsed = ControllerFrameParser.parse(bytes)
        synchronized(lock) {
            totalPackets++
            if (parsed is ParsedPacket.InputFrame) {
                val frame = parsed.frame
                latestFrame = frame
                val pressed = frame.pressedButtons.toSet()
                for (button in pressed) {
                    if (button !in previousPressed) {
                        pressCounts[button] = (pressCounts[button] ?: 0) + 1
                    }
                }
                previousPressed = pressed
                if (frame.touchpadTouched) {
                    trail.addLast(frame.touchpadX to frame.touchpadY)
                    while (trail.size > TRAIL_POINTS) {
                        trail.removeFirst()
                    }
                }
            }
            rawLines.addLast(formatLine(parsed))
            while (rawLines.size > LOG_LINES) {
                rawLines.removeFirst()
            }
        }
    }

    private fun formatLine(parsed: ParsedPacket): String {
        val stamp = timeFormat.format(Date())
        return when (parsed) {
            is ParsedPacket.InputFrame ->
                "$stamp  len=${parsed.frame.raw.size}  ${parsed.frame.rawHex()}"

            is ParsedPacket.UnknownFrame ->
                "$stamp  len=${parsed.length}  ${hexOf(parsed.raw)}"
        }
    }

    private fun hexOf(bytes: ByteArray): String =
        if (bytes.isEmpty()) "(空)" else bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private val liveTick = object : Runnable {
        override fun run() {
            renderLive()
            handler.postDelayed(this, LIVE_INTERVAL_MS)
        }
    }

    private val logTick = object : Runnable {
        override fun run() {
            renderLog()
            handler.postDelayed(this, LOG_INTERVAL_MS)
        }
    }

    private fun startTicks() {
        lastRateAt = SystemClock.elapsedRealtime()
        handler.post(liveTick)
        handler.post(logTick)
    }

    private fun renderLive() {
        val frame: ControllerFrame?
        val counts: Map<ControllerButton, Int>
        val points: List<Pair<Int, Int>>
        synchronized(lock) {
            frame = latestFrame
            counts = HashMap(pressCounts)
            points = trail.toList()
        }

        for ((button, view) in keyViews) {
            val pressed = frame?.isPressed(button) == true
            view.backgroundTintList =
                ColorStateList.valueOf(if (pressed) COLOR_KEY_ON else COLOR_KEY_OFF)
            view.setTextColor(if (pressed) Color.WHITE else COLOR_TEXT)
            view.text = "${button.label}\n${counts[button] ?: 0}"
        }

        val touched = frame?.touchpadTouched == true
        touchpadView.current = if (touched) frame?.let { it.touchpadX to it.touchpadY } else null
        touchpadView.trail = points

        touchpadText.text = when {
            frame == null -> "等待数据…"
            touched -> "x=${frame.touchpadX}  y=${frame.touchpadY}  触摸中"
            else -> "x=${frame.touchpadX}  y=${frame.touchpadY}  未触摸"
        }

        if (frame != null) {
            motionView.accX = frame.accX
            motionView.accY = frame.accY
            motionView.gyroX = frame.gyroX
            motionView.gyroY = frame.gyroY
            motionView.gyroZ = frame.gyroZ
            motionText.text = "四元数 ${quad(frame)}   加速度 " +
                "${axis(frame.accX)}, ${axis(frame.accY)}, ${axis(frame.accZ)} g   陀螺仪 " +
                "${axis(frame.gyroX)}, ${axis(frame.gyroY)}, ${axis(frame.gyroZ)} °/s"
        } else {
            motionText.text = "体感等待数据…"
        }

        val ready = currentState as? ConnectionState.Ready
        deviceText.text = if (ready != null) {
            "${ready.address}   电量 ${frame?.battery ?: 0}%   " +
                "标记 ${if (frame?.markerOk == true) "OK" else "缺失"}"
        } else {
            "--"
        }
    }

    private fun renderLog() {
        val text: String
        val count: Int
        synchronized(lock) {
            text = rawLines.joinToString("\n")
            count = totalPackets
        }
        rawLog.text = text
        rawScroll.post { rawScroll.fullScroll(View.FOCUS_DOWN) }

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastRateAt
        if (elapsed >= 1_000) {
            packetsPerSecond = ((count - lastRateCount) * 1_000 / elapsed).toInt()
            lastRateAt = now
            lastRateCount = count
        }
        statsText.text = "包/秒 $packetsPerSecond   累计 $count"
    }

    private fun renderState(state: ConnectionState) {
        currentState = state
        val color = when (state) {
            is ConnectionState.Ready -> COLOR_OK
            is ConnectionState.Failed, is ConnectionState.Disconnected -> COLOR_BAD
            is ConnectionState.Scanning -> COLOR_WARN
            else -> COLOR_TEXT
        }
        stateText.text = state.label
        stateText.setTextColor(color)
        scanButton.text = if (RemoteCore.isScanning()) "停止扫描" else "开始扫描"
        scanButton.isEnabled = state !is ConnectionState.Ready &&
            state !is ConnectionState.Connecting
    }

    private fun renderDevices(devices: List<DeviceEntry>) {
        val connectedAddress = (currentState as? ConnectionState.Ready)?.address
        val signature = devices.joinToString("|") { "${it.address}:${it.rssi}:${it.bonded}" } +
            "|connected=$connectedAddress"
        if (signature == lastDeviceSignature) return
        lastDeviceSignature = signature

        deviceList.removeAllViews()
        if (devices.isEmpty()) {
            deviceList.addView(rowView("还没有设备。点“开始扫描”，发现手柄后会自动连接。"))
            return
        }
        for (entry in devices) {
            val row = rowView(deviceLabel(entry))
            if (entry.isController) row.setOnClickListener { RemoteCore.connect(entry.address) }
            deviceList.addView(row)
        }
    }

    private fun deviceLabel(entry: DeviceEntry): String {
        val rssi = if (entry.rssi != 0) "  RSSI ${entry.rssi}" else ""
        val connected = (currentState as? ConnectionState.Ready)?.address == entry.address
        val tag = when {
            connected -> "  [已连接]"
            entry.bonded -> "  [系统已配对]"
            else -> ""
        }
        return "${entry.name}$tag\n${entry.address}$rssi"
    }

    private fun rowView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(COLOR_TEXT)
        setPadding(20, 16, 20, 16)
        background = GradientDrawable().apply {
            cornerRadius = 10f
            setColor(0xFF232B34.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = 6 }
    }

    private fun quad(frame: ControllerFrame): String =
        "x${axis(frame.rotationX)} y${axis(frame.rotationY)} " +
            "z${axis(frame.rotationZ)} w${axis(frame.rotationW)}"

    private fun axis(value: Float): String = "%+.2f".format(value)

    private fun clearLogs() {
        synchronized(lock) {
            rawLines.clear()
            totalPackets = 0
            lastRateCount = 0
            lastRateAt = SystemClock.elapsedRealtime()
        }
        rawLog.text = ""
        showStatus("日志已清空")
    }

    private fun copyLogs() {
        val text = synchronized(lock) { rawLines.joinToString("\n") }
        if (text.isEmpty()) {
            showStatus("没有可复制的数据")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("vrremote-log", text))
        showStatus("已复制 ${rawLines.size} 行")
    }

    /**
     * Feedback lives inside the page: the app draws every message itself, so no
     * SystemUI hook can rewrite the text on the way through.
     */
    private fun showStatus(message: String) {
        statusText.text = message
        handler.removeCallbacks(clearStatus)
        handler.postDelayed(clearStatus, STATUS_DURATION_MS)
    }

    private val clearStatus = Runnable { statusText.text = "" }

    private fun requiredPermissions(): Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasBluetoothPermissions(): Boolean = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (!(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })) {
            stateText.text = "缺少蓝牙权限，无法扫描或连接"
            stateText.setTextColor(COLOR_BAD)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

}
