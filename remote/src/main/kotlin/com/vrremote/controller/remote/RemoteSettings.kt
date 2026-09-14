package com.vrremote.controller.remote

/**
 * Tunables exposed on the settings screen.
 *
 * Defaults were chosen so the controller feels right on a normal phone: one
 * direction key per ~12% of the touchpad travel, a full touchpad sweep moving
 * the cursor across roughly one screen, and a motion dead zone small enough to
 * stay responsive while ignoring hand tremor.
 */
data class RemoteSettings(
    /** Touchpad travel (0–255 scale) that produces one direction key press. */
    val dpadStep: Int = 30,
    /** Pixels of cursor movement per touchpad unit in touchpad fly mode. */
    val flyTouchGain: Float = 6f,
    /** Pixels of cursor movement per radian of rotation in motion fly mode. */
    val flyMotionGain: Float = 2600f,
    /** Rotation smaller than this (in radians) does not move the cursor. */
    val flyMotionDeadZone: Float = 0.001f,
    /** Scales every gesture distance, 1.0 is the default amplitude. */
    val gestureScale: Float = 1f,
)
