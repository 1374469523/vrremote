package com.vrremote.controller.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import com.vrremote.controller.BuildConfig
import com.vrremote.controller.remote.RemoteAction
import com.vrremote.controller.remote.SwipeDirection
import com.vrremote.controller.remote.TapPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * App-side half of the injection channel.
 *
 * Shizuku runs [InputService] with the shell identity; this class owns the
 * permission flow, the binding and the key tap timing. Its states mirror what
 * the UI has to explain, so a failure is never silent.
 */
class InputBridge(private val context: Context) {

    sealed interface State {
        /** Shizuku (or Sui) is not installed on this device. */
        data object ShizukuMissing : State

        /** Installed but not running, e.g. it was not started after a reboot. */
        data object ShizukuStopped : State

        /** Running, but this app was never granted access. */
        data object PermissionNeeded : State

        data object Connecting : State

        data object Ready : State

        data class Failed(val message: String) : State
    }

    private companion object {
        const val TAG = "VrRemoteInput"
        const val PERMISSION_REQUEST_CODE = 4210
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /** How long a key stays down; the controller itself only sends edges. */
        const val TAP_HOLD_MS = 20L

        /** A relative HID report carries signed bytes. */
        const val MAX_REPORT_DELTA = 127

        /** How long a synthetic swipe takes. */
        const val SWIPE_DURATION_MS = 200
    }

    private val _state = MutableStateFlow<State>(State.ShizukuMissing)
    val state: StateFlow<State> = _state.asStateFlow()

    private val tapExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "input-tap").apply { isDaemon = true }
    }

    private var service: IControllerInput? = null
    private var bound = false

    /** Scales every gesture distance; kept in sync with the settings screen. */
    var gestureScale: Float = 1f

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, InputService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("input")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                connect()
            } else {
                _state.value = State.PermissionNeeded
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        bound = false
        _state.value = State.ShizukuStopped
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                _state.value = State.Failed("Shizuku 用户服务连接失败")
                return
            }
            val input = IControllerInput.Stub.asInterface(binder)
            val result = try {
                input.createDevices()
            } catch (error: Exception) {
                Log.w(TAG, "createDevices failed", error)
                _state.value = State.Failed("创建虚拟键鼠失败：${error.message}")
                return
            }
            if (result != 0) {
                _state.value = State.Failed(describeOpenFailure(result))
                return
            }
            service = input
            // Best effort: lets the app draw its own mode hint instead of a
            // toast, which some devices rewrite.
            try {
                input.grantOverlayPermission(context.packageName)
            } catch (error: Exception) {
                Log.w(TAG, "overlay appop not granted", error)
            }
            _state.value = State.Ready
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            _state.value = State.Failed("Shizuku 用户服务已断开")
        }
    }

    fun start() {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        refresh()
    }

    fun stop() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (ignored: IllegalStateException) {
        }
        disconnect()
    }

    /** Re-evaluates the Shizuku state and binds the user service when allowed. */
    fun refresh() {
        // Never downgrade a live channel: the activities call this on every
        // resume, and reporting "connecting" while the service is up would sit
        // on screen forever because nothing re-binds.
        if (service != null) {
            _state.value = State.Ready
            return
        }
        val next = when {
            !Shizuku.pingBinder() && !isShizukuInstalled() -> State.ShizukuMissing
            !Shizuku.pingBinder() -> State.ShizukuStopped
            Shizuku.isPreV11() -> State.Failed("Shizuku 版本过旧，需要 11 或更高")
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.Connecting
            else -> State.PermissionNeeded
        }
        _state.value = next
        if (next == State.Connecting) connect()
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            _state.value = if (isShizukuInstalled()) State.ShizukuStopped else State.ShizukuMissing
            return
        }
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
    }

    private fun connect() {
        if (bound) return
        _state.value = State.Connecting
        try {
            Shizuku.bindUserService(serviceArgs, connection)
            bound = true
        } catch (error: Throwable) {
            Log.w(TAG, "bindUserService failed", error)
            _state.value = State.Failed("无法绑定 Shizuku 用户服务：${error.message}")
        }
    }

    fun disconnect() {
        val current = service
        service = null
        if (current != null) {
            try {
                current.closeDevices()
            } catch (ignored: Exception) {
            }
        }
        if (bound) {
            bound = false
            try {
                Shizuku.unbindUserService(serviceArgs, connection, true)
            } catch (ignored: Exception) {
            }
        }
    }

    /** Sends one engine action to the virtual devices. */
    fun send(action: RemoteAction) {
        val input = service ?: return
        try {
            when (action) {
                is RemoteAction.KeyPress -> sendKey(input, action.keyCode)
                is RemoteAction.PointerMove -> movePointer(input, action.dx, action.dy)
                is RemoteAction.PointerButton -> input.mouseButton(action.button, action.down)
                is RemoteAction.Swipe -> swipe(input, action.direction)
                is RemoteAction.Tap -> tapAt(input, action.position)
                is RemoteAction.DoubleTap -> {
                    val point = pointOf(action.position)
                    input.doubleTap(point.x, point.y)
                }

                is RemoteAction.LongPress -> {
                    val point = pointOf(action.position)
                    input.longPress(point.x, point.y)
                }

                RemoteAction.CycleMode -> Unit
            }
        } catch (error: Exception) {
            Log.w(TAG, "cannot send $action", error)
        }
    }

    /**
     * Keys the virtual devices can express go through HID, which is instant.
     * Anything else is handed to `input keyevent` inside the user service, so a
     * key can never silently do nothing.
     */
    private fun sendKey(input: IControllerInput, keyCode: Int) {
        if (HidDescriptors.canEmit(keyCode)) {
            tap(input, keyCode)
        } else {
            Log.i(TAG, "key $keyCode has no HID usage, sending it through input keyevent")
            input.keyEvent(keyCode)
        }
    }

    /** Package of the app in the foreground, or null when it cannot be read. */
    fun foregroundPackage(): String? = try {
        service?.foregroundPackage()
    } catch (error: Exception) {
        Log.w(TAG, "cannot read the foreground package", error)
        null
    }

    // ------------------------------------------------------------- gestures

    private fun swipe(input: IControllerInput, direction: SwipeDirection) {
        val size = screenSize()
        val scale = gestureScale.coerceIn(0.2f, 3f)
        val centreX = size.x / 2f
        val centreY = size.y / 2f
        val horizontal = size.x * 0.2f * scale
        val vertical = size.y * 0.15f * scale
        val fromX: Float
        val fromY: Float
        val toX: Float
        val toY: Float
        when (direction) {
            SwipeDirection.UP -> {
                fromX = centreX; fromY = centreY + vertical
                toX = centreX; toY = centreY - vertical
            }

            SwipeDirection.DOWN -> {
                fromX = centreX; fromY = centreY - vertical
                toX = centreX; toY = centreY + vertical
            }

            SwipeDirection.LEFT -> {
                fromX = centreX + horizontal; fromY = centreY
                toX = centreX - horizontal; toY = centreY
            }

            SwipeDirection.RIGHT -> {
                fromX = centreX - horizontal; fromY = centreY
                toX = centreX + horizontal; toY = centreY
            }
        }
        input.swipe(fromX.toInt(), fromY.toInt(), toX.toInt(), toY.toInt(), SWIPE_DURATION_MS)
    }

    private fun tapAt(input: IControllerInput, position: TapPosition) {
        val point = pointOf(position)
        input.tap(point.x, point.y)
    }

    private fun pointOf(position: TapPosition): Point {
        val size = screenSize()
        return when (position) {
            TapPosition.LEFT -> Point((size.x * 0.25f).toInt(), size.y / 2)
            TapPosition.CENTER -> Point(size.x / 2, size.y / 2)
            TapPosition.RIGHT -> Point((size.x * 0.75f).toInt(), size.y / 2)
            TapPosition.BOTTOM -> Point(size.x / 2, (size.y * 0.88f).toInt())
        }
    }

    private fun screenSize(): Point {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * A relative mouse report carries signed bytes, so anything beyond ±127 has
     * to be split. Without this, a fast turn is silently clipped and the cursor
     * falls behind the hand.
     */
    private fun movePointer(input: IControllerInput, dx: Int, dy: Int) {
        var remainingX = dx
        var remainingY = dy
        while (remainingX != 0 || remainingY != 0) {
            val stepX = remainingX.coerceIn(-MAX_REPORT_DELTA, MAX_REPORT_DELTA)
            val stepY = remainingY.coerceIn(-MAX_REPORT_DELTA, MAX_REPORT_DELTA)
            input.mouseMove(stepX, stepY)
            remainingX -= stepX
            remainingY -= stepY
        }
    }

    private fun tap(input: IControllerInput, keyCode: Int) {
        input.key(keyCode, true)
        tapExecutor.schedule({
            try {
                input.key(keyCode, false)
            } catch (ignored: Exception) {
            }
        }, TAP_HOLD_MS, TimeUnit.MILLISECONDS)
    }

    private fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (ignored: PackageManager.NameNotFoundException) {
        false
    }

    /** Turns an errno style code from the user service into something readable. */
    private fun describeOpenFailure(code: Int): String = when (code) {
        -13 -> "系统拒绝了 /dev/uhid 的访问（EACCES）"
        -2 -> "系统里没有 /dev/uhid"
        -1 -> "打开 /dev/uhid 被拒绝（EPERM）"
        else -> "打开 /dev/uhid 失败，错误码 $code"
    }
}
