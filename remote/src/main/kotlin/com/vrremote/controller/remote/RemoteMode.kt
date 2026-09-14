package com.vrremote.controller.remote

/** The three ways the controller can drive the phone. */
enum class RemoteMode(val label: String) {
    NORMAL("普通模式"),
    FLY_TOUCH("指尖鼠标"),
    FLY_MOTION("体感鼠标"),
    ;

    /** The trigger button cycles normal → touchpad fly → motion fly → normal. */
    fun next(): RemoteMode = entries[(ordinal + 1) % entries.size]
}
