package com.vrremote.controller

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import com.vrremote.controller.input.InputBridge
import com.vrremote.controller.remote.RemoteMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The app's home page: connection, mode, remote switch and Shizuku state.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQUEST_PERMISSIONS = 10

        val COLOR_TEXT = 0xFF1B1D21.toInt()
        val COLOR_MUTED = 0xFF6B7280.toInt()
        val COLOR_OK = 0xFF1E8E3E.toInt()
        val COLOR_WARN = 0xFFB06000.toInt()
        val COLOR_BAD = 0xFFC5221F.toInt()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var stateText: TextView
    private lateinit var deviceText: TextView
    private lateinit var scanButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var remoteSwitchButton: Button
    private lateinit var modeGroup: RadioGroup
    private lateinit var shizukuText: TextView
    private lateinit var shizukuButton: Button
    private lateinit var deviceList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        RemoteCore.init(this)
        bindViews()
        observe()

        if (!hasRequiredPermissions()) {
            requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS)
        } else {
            startRemote()
        }
    }

    /**
     * The foreground service needs the Bluetooth permissions granted, otherwise
     * Android refuses the connectedDevice type outright.
     */
    private fun startRemote() {
        RemoteService.start(this)
        RemoteCore.autoScan()
    }

    private fun bindViews() {
        stateText = findViewById(R.id.stateText)
        deviceText = findViewById(R.id.deviceText)
        scanButton = findViewById(R.id.scanButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        remoteSwitchButton = findViewById(R.id.remoteSwitchButton)
        modeGroup = findViewById(R.id.modeGroup)
        shizukuText = findViewById(R.id.shizukuText)
        shizukuButton = findViewById(R.id.shizukuButton)
        deviceList = findViewById(R.id.deviceList)

        scanButton.setOnClickListener {
            if (RemoteCore.isScanning()) RemoteCore.stopScan() else RemoteCore.startScan()
        }
        disconnectButton.setOnClickListener { RemoteCore.disconnect() }
        remoteSwitchButton.setOnClickListener {
            RemoteCore.setRemoteEnabled(!RemoteCore.remoteEnabled.value)
        }
        shizukuButton.setOnClickListener {
            if (RemoteCore.inputState.value is InputBridge.State.PermissionNeeded) {
                RemoteCore.requestShizukuPermission()
            } else {
                RemoteCore.refreshInputState()
            }
        }
        findViewById<Button>(R.id.testButton).setOnClickListener {
            startActivity(Intent(this, ControllerTestActivity::class.java))
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            RemoteCore.setMode(
                when (checkedId) {
                    R.id.modeFlyTouch -> RemoteMode.FLY_TOUCH
                    R.id.modeFlyMotion -> RemoteMode.FLY_MOTION
                    R.id.modeProfile -> RemoteMode.PROFILE
                    else -> RemoteMode.NORMAL
                }
            )
        }
    }

    private fun observe() {
        scope.launch {
            RemoteCore.connectionState.collect { state -> renderState(state) }
        }
        scope.launch {
            RemoteCore.devices.collect { devices -> renderDevices(devices) }
        }
        scope.launch {
            RemoteCore.mode.collect { mode -> renderMode(mode) }
        }
        scope.launch {
            RemoteCore.remoteEnabled.collect { enabled ->
                remoteSwitchButton.text = if (enabled) "遥控已开启" else "遥控已关闭"
            }
        }
        scope.launch {
            RemoteCore.inputState.collect { state -> renderInput(state) }
        }
    }

    private fun renderState(state: ConnectionState) {
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
            state !is ConnectionState.Connecting &&
            state !is ConnectionState.Subscribing
        disconnectButton.isEnabled = state is ConnectionState.Ready ||
            state is ConnectionState.Connecting ||
            state is ConnectionState.Discovering ||
            state is ConnectionState.Subscribing

        val ready = state as? ConnectionState.Ready
        deviceText.text = when {
            ready != null -> "${ready.name}\n${ready.address}"
            else -> "发现手柄后会自动连接"
        }
    }

    private fun renderMode(mode: RemoteMode) {
        val id = when (mode) {
            RemoteMode.NORMAL -> R.id.modeNormal
            RemoteMode.FLY_TOUCH -> R.id.modeFlyTouch
            RemoteMode.FLY_MOTION -> R.id.modeFlyMotion
            RemoteMode.PROFILE -> R.id.modeProfile
        }
        if (modeGroup.checkedRadioButtonId != id) modeGroup.check(id)
    }

    private fun renderInput(state: InputBridge.State) {
        val (text, color) = when (state) {
            is InputBridge.State.Ready ->
                "已就绪，虚拟键鼠已创建" to COLOR_OK

            is InputBridge.State.Connecting ->
                "正在连接 Shizuku…" to COLOR_TEXT

            is InputBridge.State.PermissionNeeded ->
                "需要在本应用里授权 Shizuku 才能遥控" to COLOR_WARN

            is InputBridge.State.ShizukuStopped ->
                "Shizuku 没有运行，请在 Shizuku 应用里启动它" to COLOR_WARN

            is InputBridge.State.ShizukuMissing ->
                "没有检测到 Shizuku，请先安装 Shizuku" to COLOR_BAD

            is InputBridge.State.Failed ->
                state.message to COLOR_BAD
        }
        shizukuText.text = text
        shizukuText.setTextColor(color)
        shizukuButton.text = when (state) {
            is InputBridge.State.PermissionNeeded -> "授权"
            is InputBridge.State.ShizukuMissing -> "重新检测"
            else -> "重新检测"
        }
    }

    private fun renderDevices(devices: List<DeviceEntry>) {
        val signature = devices.joinToString("|") { "${it.address}:${it.rssi}:${it.bonded}" }
        if (signature == lastDeviceSignature) return
        lastDeviceSignature = signature

        deviceList.removeAllViews()
        if (devices.isEmpty()) {
            deviceList.addView(rowView("附近还没有发现设备，正在扫描…"))
            return
        }
        for (entry in devices) {
            val suffix = if (entry.rssi != 0) "   RSSI ${entry.rssi}" else ""
            val row = rowView("${entry.name}\n${entry.address}$suffix")
            if (entry.isController) {
                row.setOnClickListener { RemoteCore.connect(entry.address) }
            } else {
                // Only the controller can be connected; other rows are context.
                row.setTextColor(COLOR_MUTED)
            }
            deviceList.addView(row)
        }
    }

    private var lastDeviceSignature = ""

    private fun rowView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(COLOR_TEXT)
        setPadding(24, 20, 24, 20)
        background = GradientDrawable().apply {
            cornerRadius = 10f
            setColor(0xFFF1F3F6.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = 8 }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    private fun hasRequiredPermissions(): Boolean = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val bluetoothGranted = permissions.indices
            .filter { permissions[it] != Manifest.permission.POST_NOTIFICATIONS }
            .all { grantResults[it] == PackageManager.PERMISSION_GRANTED }
        if (bluetoothGranted) {
            startRemote()
        } else {
            stateText.text = "缺少蓝牙权限，无法扫描手柄"
            stateText.setTextColor(COLOR_BAD)
        }
    }

    override fun onResume() {
        super.onResume()
        if (RemoteCore.isInitialised) {
            RemoteCore.refreshInputState()
            if (hasRequiredPermissions() && RemoteCore.connectionState.value !is ConnectionState.Ready) {
                RemoteCore.autoScan()
            }
        }
        renderMode(RemoteCore.mode.value)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

}
