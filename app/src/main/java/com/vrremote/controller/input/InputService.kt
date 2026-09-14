package com.vrremote.controller.input

import android.util.Log

/**
 * Runs inside the Shizuku user service process with the shell identity, which
 * is what makes /dev/uhid reachable without root.
 *
 * Shizuku instantiates this class by name, so it must stay public and keep a
 * no-argument constructor.
 */
class InputService : IControllerInput.Stub() {

    private companion object {
        const val TAG = "VrRemoteInput"
        const val MAX_KEYS = 6
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
        consumer?.send(byteArrayOf(consumerState.toByte()))
    }
}
