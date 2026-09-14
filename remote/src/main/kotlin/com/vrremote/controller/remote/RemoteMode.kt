package com.vrremote.controller.remote

/** The ways the controller can drive the phone. */
enum class RemoteMode(val label: String) {
    NORMAL("普通模式"),
    FLY_TOUCH("指尖鼠标"),
    FLY_MOTION("体感鼠标"),
    PROFILE("跟随应用"),
    ;

    /** The trigger button cycles through every mode in declaration order. */
    fun next(): RemoteMode = entries[(ordinal + 1) % entries.size]
}
