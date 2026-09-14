package com.vrremote.controller.input

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs inside the Shizuku user service process with the shell identity, which
 * is what makes /dev/uhid reachable without root.
 *
 * Shizuku instantiates this class by name, so it must stay public and keep a
 * no-argument constructor.
 */
class InputService : IControllerInput.Stub {

    private var context: Context? = null

    /** Shizuku instantiates this when no Context is available. */
    @Suppress("unused")
    constructor() : super()

    /** Shizuku can hand the service a Context; used to look up the top task. */
    @Suppress("unused")
    constructor(context: Context) : super() {
        this.context = context
    }

    private companion object {
        const val TAG = "VrRemoteInput"
        const val MAX_KEYS = 6
        const val DOUBLE_TAP_GAP_MS = 90L
        const val LONG_PRESS_MS = 700
        const val INPUT_BINARY = "/system/bin/input"
        const val CONFIG_TIMEOUT_SECONDS = 5L

        val FOREGROUND_PATTERN = Regex("topResumedActivity.*? ([A-Za-z0-9_.]+)/")
    }

    /**
     * Gestures are serialised on one thread: two synthetic touches overlapping
     * would confuse the app being controlled.
     */
    private val gestureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gesture").apply { isDaemon = true }
    }

    private var keyboard: UhidDevice? = null
    private var consumer: UhidDevice? = null
    private var mouse: UhidDevice? = null

    private val pressedUsages = sortedSetOf<Int>()
    private var consumerState = 0
    private var mouseButtons = 0

    override fun createDevices(): Int {
        closeDevices()

        val keyboardDevice = UhidDevice("VrRemote Keyboard", HidDescriptors.KEYBOARD)
        keyboardDevice.open().takeIf { it != 0 }?.let { return it }

        val consumerDevice = UhidDevice("VrRemote Consumer", HidDescriptors.CONSUMER)
        consumerDevice.open().takeIf { it != 0 }?.let {
            keyboardDevice.close()
            return it
        }

        val mouseDevice = UhidDevice("VrRemote Mouse", HidDescriptors.MOUSE)
        mouseDevice.open().takeIf { it != 0 }?.let {
            keyboardDevice.close()
            consumerDevice.close()
            return it
        }

        keyboard = keyboardDevice
        consumer = consumerDevice
        mouse = mouseDevice
        pressedUsages.clear()
        consumerState = 0
        mouseButtons = 0
        Log.i(TAG, "virtual keyboard, consumer control and mouse are ready")
        return 0
    }

    override fun closeDevices() {
        keyboard?.close()
        consumer?.close()
        mouse?.close()
        keyboard = null
        consumer = null
        mouse = null
        pressedUsages.clear()
        consumerState = 0
        mouseButtons = 0
    }

    override fun key(keyCode: Int, down: Boolean) {
        val usage = HidDescriptors.keyboardUsage(keyCode)
        if (usage != null) {
            if (down) {
                if (pressedUsages.size < MAX_KEYS) pressedUsages.add(usage)
            } else {
                pressedUsages.remove(usage)
            }
            sendKeyboardReport()
            return
        }
        val bit = HidDescriptors.consumerBit(keyCode)
        if (bit != null) {
            consumerState = if (down) consumerState or bit else consumerState and bit.inv()
            sendConsumerReport()
            return
        }
        Log.w(TAG, "no HID usage for key code $keyCode")
    }

    override fun mouseMove(dx: Int, dy: Int) {
        val report = byteArrayOf(
            mouseButtons.toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            0,
        )
        mouse?.send(report)
    }

    override fun mouseButton(button: Int, down: Boolean) {
        val bit = 1 shl ((button - 1).coerceIn(0, 2))
        mouseButtons = if (down) mouseButtons or bit else mouseButtons and bit.inv()
        val report = byteArrayOf(mouseButtons.toByte(), 0, 0, 0)
        mouse?.send(report)
    }

    override fun destroy() {
        closeDevices()
        Log.i(TAG, "user service destroyed")
        System.exit(0)
    }

    override fun grantOverlayPermission(packageName: String): Boolean {
        return try {
            val process = ProcessBuilder(
                "appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow",
            ).redirectErrorStream(true).start()
            val finished = process.waitFor()
            val output = process.inputStream.bufferedReader().readText().trim()
            Log.i(TAG, "appops exit=$finished $output")
            finished == 0
        } catch (error: Exception) {
            Log.w(TAG, "cannot grant overlay appop", error)
            false
        }
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        gestureExecutor.execute {
            runInput("swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())
        }
    }

    override fun tap(x: Int, y: Int) {
        gestureExecutor.execute { runInput("tap", x.toString(), y.toString()) }
    }

    override fun doubleTap(x: Int, y: Int) {
        gestureExecutor.execute {
            runInput("tap", x.toString(), y.toString())
            try {
                Thread.sleep(DOUBLE_TAP_GAP_MS)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
                return@execute
            }
            runInput("tap", x.toString(), y.toString())
        }
    }

    override fun longPress(x: Int, y: Int) {
        gestureExecutor.execute {
            runInput(
                "swipe",
                x.toString(), y.toString(), x.toString(), y.toString(), LONG_PRESS_MS.toString(),
            )
        }
    }

    override fun keyEvent(keyCode: Int) {
        gestureExecutor.execute { runInput("keyevent", keyCode.toString()) }
    }

    override fun foregroundPackage(): String? {
        runningTasksPackage()?.let { return it }
        return dumpsysPackage()
    }

    private fun runningTasksPackage(): String? = try {
        val manager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.getRunningTasks(1)?.firstOrNull()?.topActivity?.packageName
    } catch (error: Exception) {
        Log.w(TAG, "getRunningTasks failed", error)
        null
    }

    /**
     * Fallback for devices where the task stack is not readable: the same
     * information the shell prints in `dumpsys activity activities`.
     */
    private fun dumpsysPackage(): String? {
        return try {
            val process = ProcessBuilder("dumpsys", "activity", "activities")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(CONFIG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                closeQuietly(process)
                Log.w(TAG, "dumpsys activity timed out")
                return null
            }
            FOREGROUND_PATTERN.find(output)?.groupValues?.get(1)
        } catch (error: Exception) {
            Log.w(TAG, "dumpsys activity failed", error)
            null
        }
    }

    private fun runInput(vararg args: String) {
        try {
            val command = mutableListOf(INPUT_BINARY)
            command.addAll(args)
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val finished = process.waitFor(CONFIG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                // Close the pipes so a child blocked on writing cannot linger.
                closeQuietly(process)
                Log.w(TAG, "input ${args.joinToString(" ")} timed out")
            }
        } catch (error: Exception) {
            Log.w(TAG, "input ${args.joinToString(" ")} failed", error)
        }
    }

    private fun closeQuietly(process: Process) {
        for (stream in listOf(process.outputStream, process.inputStream, process.errorStream)) {
            try {
                stream.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun sendKeyboardReport() {
        val report = ByteArray(HidDescriptors.KEYBOARD_REPORT_SIZE)
        var index = 2
        for (usage in pressedUsages) {
            if (index >= report.size) break
            report[index] = usage.toByte()
            index++
        }
        keyboard?.send(report)
    }

    private fun sendConsumerReport() {
        // The consumer report grew to two bytes when the media, menu and app
        // switch usages were added; little endian, low bits first.
        consumer?.send(
            byteArrayOf(
                (consumerState and 0xFF).toByte(),
                ((consumerState shr 8) and 0xFF).toByte(),
            )
        )
    }
}
