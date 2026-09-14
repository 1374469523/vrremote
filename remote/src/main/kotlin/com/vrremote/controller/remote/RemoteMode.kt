package com.vrremote.controller.remote

/** The three ways the controller can drive the phone. */
enum class RemoteMode(val label: String) {
    NORMAL("普通模式"),
    FLY_TOUCH("飞鼠1 · 触控板"),
    FLY_MOTION("飞鼠2 · 体感"),
    ;

    /** The trigger button cycles normal → touchpad fly → motion fly → normal. */
    fun next(): RemoteMode = entries[(ordinal + 1) % entries.size]
}
