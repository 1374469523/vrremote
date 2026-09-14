package com.vrremote.controller

import android.content.Context
import android.util.Log
import com.vrremote.controller.input.InputBridge
import com.vrremote.controller.remote.RemoteAction
import com.vrremote.controller.remote.RemoteEngine
import com.vrremote.controller.remote.RemoteMode
import com.vrremote.controller.remote.RemoteSettings
import com.vrremote.controller.remote.foregroundUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * The running app: bluetooth link, mapping engine and injection channel.
 *
 * Everything lives in one process, so the activities and the foreground
 * service share this object instead of talking over a binder.
 */
object RemoteCore {

    private const val TAG = "VrRemoteCore"
    private const val FOREGROUND_POLL_MS = 400L

    private lateinit var appContext: Context
    private lateinit var preferences: Preferences
    private lateinit var transport: BleControllerTransport
    private lateinit var bridge: InputBridge
    private lateinit var engine: RemoteEngine
    private lateinit var modeHint: ModeHintOverlay
    private lateinit var profileStore: ProfileStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _mode = MutableStateFlow(RemoteMode.NORMAL)
    private val _remoteEnabled = MutableStateFlow(true)
    private val _started = MutableStateFlow(false)
    private val _foregroundPackage = MutableStateFlow<String?>(null)

    val mode: StateFlow<RemoteMode> = _mode.asStateFlow()
    val remoteEnabled: StateFlow<Boolean> = _remoteEnabled.asStateFlow()
    val started: StateFlow<Boolean> = _started.asStateFlow()

    /** Package of the app in front; only tracked while the profile mode is on. */
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    lateinit var connectionState: StateFlow<ConnectionState>
        private set
    lateinit var devices: StateFlow<List<DeviceEntry>>
        private set
    lateinit var packets: SharedFlow<ByteArray>
        private set
    lateinit var inputState: StateFlow<InputBridge.State>
        private set

    val isInitialised: Boolean get() = _started.value

    fun init(context: Context) {
        if (_started.value) return
        appContext = context.applicationContext
        preferences = Preferences(appContext)
        profileStore = ProfileStore(appContext)
        transport = BleControllerTransport(appContext)
        bridge = InputBridge(appContext)
        engine = RemoteEngine(
            initialMode = preferences.mode,
            settings = { preferences.remoteSettings() },
            profile = { _foregroundPackage.value?.let { profileStore.find(it) } },
        )
        modeHint = ModeHintOverlay(appContext)
        bridge.gestureScale = preferences.gestureScale
        _mode.value = preferences.mode
        _remoteEnabled.value = preferences.remoteEnabled

        connectionState = transport.state
        devices = transport.devices
        packets = transport.packets
        inputState = bridge.state

        bridge.start()
        transport.start()
        observe()
        _started.value = true
    }

    private fun observe() {
        scope.launch {
            transport.packets.collect { bytes ->
                val frame = when (val parsed = com.vrremote.controller.protocol.ControllerFrameParser.parse(bytes)) {
                    is com.vrremote.controller.protocol.ParsedPacket.InputFrame -> parsed.frame
                    is com.vrremote.controller.protocol.ParsedPacket.UnknownFrame -> null
                } ?: return@collect

                val actions = engine.onFrame(frame).toMutableList()
                if (actions.remove(RemoteAction.CycleMode)) {
                    actions.addAll(0, cycleMode())
                }
                dispatch(actions)
            }
        }
        scope.launch {
            transport.state.collect { state ->
                if (state is ConnectionState.Disconnected || state is ConnectionState.Failed) {
                    dispatch(engine.reset())
                }
            }
        }
        // A binder call per poll must not run on the main thread.
        scope.launch(Dispatchers.IO) {
            _mode.collect { mode ->
                if (mode == RemoteMode.PROFILE) {
                    pollForegroundPackage()
                } else {
                    _foregroundPackage.value = null
                }
            }
        }
    }

    /**
     * Only the profile mode cares which app is in front, so the lookup runs only
     * while it is active: one binder call every 400 ms, no process spawn.
     */
    private suspend fun pollForegroundPackage() {
        while (scope.isActive && _mode.value == RemoteMode.PROFILE) {
            val update = foregroundUpdate(_foregroundPackage.value, bridge.foregroundPackage())
            if (update.changed) {
                val packageName = update.packageName
                if (packageName == null) {
                    Log.w(TAG, "cannot read the foreground app, profile mode is idle")
                } else {
                    Log.i(TAG, "foreground app is now $packageName")
                }
                _foregroundPackage.value = packageName
            }
            delay(FOREGROUND_POLL_MS)
        }
    }

    private fun dispatch(actions: List<RemoteAction>) {
        if (!_remoteEnabled.value) return
        for (action in actions) bridge.send(action)
    }

    /** Advances to the next mode and tells the user about it. */
    fun cycleMode(): List<RemoteAction> {
        val actions = engine.cycleMode()
        applyMode(engine.mode)
        return actions
    }

    fun setMode(mode: RemoteMode) {
        if (mode == engine.mode) return
        dispatch(engine.setMode(mode))
        applyMode(mode)
    }

    private fun applyMode(mode: RemoteMode) {
        preferences.mode = mode
        _mode.value = mode
        if (!preferences.modeHints) return
        // One path only: the app draws the hint itself. A system toast would be
        // rewritten by SystemUI hooks on some devices (measured on the test
        // handset), so there is no fallback to it.
        if (!modeHint.show(mode.label)) {
            Log.w(TAG, "mode hint could not be shown for ${mode.label}")
        }
    }

    fun setRemoteEnabled(enabled: Boolean) {
        preferences.remoteEnabled = enabled
        _remoteEnabled.value = enabled
        if (!enabled) dispatch(engine.reset())
    }

    fun reloadSettings() {
        // The engine reads settings through a lambda, so the next frame already
        // picks up new values; the bridge needs the gesture scale pushed.
        if (_started.value) bridge.gestureScale = preferences.gestureScale
    }

    /** Per-app profiles, used by the app list and editor screens. */
    val profiles: ProfileStore get() = profileStore

    fun startScan() = transport.startScan()

    fun autoScan() = transport.autoScan()

    fun stopScan() = transport.stopScan()

    fun isScanning() = transport.isScanning()

    fun disconnect() = transport.disconnect()

    fun connect(address: String) {
        transport.devices.value.firstOrNull { it.address == address }?.let { transport.connect(it) }
    }

    fun refreshInputState() = bridge.refresh()

    fun requestShizukuPermission() = bridge.requestPermission()

    fun shutdown() {
        if (!_started.value) return
        transport.stop()
        bridge.stop()
        // The collectors are started by init(); without this a restarted service
        // would stack another set on top of the ones that are already running.
        scope.coroutineContext.cancelChildren()
        _foregroundPackage.value = null
        _started.value = false
        Log.i(TAG, "shut down")
    }
}
