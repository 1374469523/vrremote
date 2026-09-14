package com.vrremote.controller

import android.content.Context
import com.vrremote.controller.remote.RemoteMode
import com.vrremote.controller.remote.RemoteSettings

/** Everything the user can tune, stored in one place. */
class Preferences(context: Context) {

    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)

    var mode: RemoteMode
        get() = runCatching { RemoteMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(RemoteMode.NORMAL)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var remoteEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var modeHints: Boolean
        get() = prefs.getBoolean(KEY_HINTS, true)
        set(value) = prefs.edit().putBoolean(KEY_HINTS, value).apply()

    var dpadStep: Int
        get() = prefs.getInt(KEY_DPAD_STEP, DEFAULT.dpadStep)
        set(value) = prefs.edit().putInt(KEY_DPAD_STEP, value).apply()

    var flyTouchGain: Float
        get() = prefs.getFloat(KEY_TOUCH_GAIN, DEFAULT.flyTouchGain)
        set(value) = prefs.edit().putFloat(KEY_TOUCH_GAIN, value).apply()

    var flyMotionGain: Float
        get() = prefs.getFloat(KEY_MOTION_GAIN, DEFAULT.flyMotionGain)
        set(value) = prefs.edit().putFloat(KEY_MOTION_GAIN, value).apply()

    var flyMotionDeadZone: Float
        get() = prefs.getFloat(KEY_MOTION_DEAD_ZONE, DEFAULT.flyMotionDeadZone)
        set(value) = prefs.edit().putFloat(KEY_MOTION_DEAD_ZONE, value).apply()

    var gestureScale: Float
        get() = prefs.getFloat(KEY_GESTURE_SCALE, DEFAULT.gestureScale)
        set(value) = prefs.edit().putFloat(KEY_GESTURE_SCALE, value).apply()

    fun remoteSettings() = RemoteSettings(
        dpadStep = dpadStep,
        flyTouchGain = flyTouchGain,
        flyMotionGain = flyMotionGain,
        flyMotionDeadZone = flyMotionDeadZone,
        gestureScale = gestureScale,
    )

    private companion object {
        val DEFAULT = RemoteSettings()

        const val KEY_MODE = "mode"
        const val KEY_ENABLED = "remote_enabled"
        const val KEY_HINTS = "mode_hints"
        const val KEY_DPAD_STEP = "dpad_step"
        const val KEY_TOUCH_GAIN = "touch_gain"
        const val KEY_MOTION_GAIN = "motion_gain"
        const val KEY_MOTION_DEAD_ZONE = "motion_dead_zone"
        const val KEY_GESTURE_SCALE = "gesture_scale"
    }
}
