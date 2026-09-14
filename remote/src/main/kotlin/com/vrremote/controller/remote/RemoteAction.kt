package com.vrremote.controller.remote

/**
 * Android key codes the remote emits.
 *
 * They match [android.view.KeyEvent] constants; the module stays free of
 * Android classes so the mapping rules remain unit testable.
 */
object RemoteKey {
    const val HOME = 3
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val ENTER = 66
}

/** Mouse buttons, matching the HID button usage numbers. */
object RemoteButton {
    const val LEFT = 1
}

/** One thing the controller wants the phone to do. */
sealed interface RemoteAction {

    data class KeyPress(val keyCode: Int) : RemoteAction

    data class PointerMove(val dx: Int, val dy: Int) : RemoteAction

    data class PointerButton(val button: Int, val down: Boolean) : RemoteAction

    /** The trigger was pressed; the owner advances to the next mode. */
    data object CycleMode : RemoteAction
}
