package com.vrremote.controller.remote

/** The controller inputs a profile can bind. The trigger always switches modes. */
enum class ProfileTrigger(val label: String) {
    TOUCH_UP("触控板上滑"),
    TOUCH_DOWN("触控板下滑"),
    TOUCH_LEFT("触控板左滑"),
    TOUCH_RIGHT("触控板右滑"),
    TOUCH_PRESS("触控板按下"),
    VOLUME_UP("音量加键"),
    VOLUME_DOWN("音量减键"),
}

/**
 * What a trigger can do inside a configured app.
 *
 * Android key events come first because they are the same on every phone and in
 * every app that follows the platform conventions: volume, media transport,
 * page turning, the camera shutter key and the d-pad. Screen gestures are the
 * fallback for apps that only react to touches (a like on a short video feed,
 * for example), and they are labelled as such because they depend on where the
 * controls happen to sit on the screen.
 */
enum class ProfileAction(val label: String) {
    NONE("不设置"),

    KEY_BACK("返回键"),
    KEY_HOME("主页键"),
    KEY_ENTER("确定键"),
    KEY_MENU("菜单键"),
    KEY_APP_SWITCH("最近任务"),
    KEY_DPAD_UP("方向键 上"),
    KEY_DPAD_DOWN("方向键 下"),
    KEY_DPAD_LEFT("方向键 左"),
    KEY_DPAD_RIGHT("方向键 右"),
    KEY_VOLUME_UP("音量加"),
    KEY_VOLUME_DOWN("音量减"),
    KEY_VOLUME_MUTE("静音"),
    KEY_MEDIA_PLAY_PAUSE("播放/暂停"),
    KEY_MEDIA_NEXT("下一个"),
    KEY_MEDIA_PREVIOUS("上一个"),
    KEY_MEDIA_REWIND("快退"),
    KEY_MEDIA_FAST_FORWARD("快进"),
    KEY_PAGE_UP("上一页"),
    KEY_PAGE_DOWN("下一页"),
    KEY_CAMERA("拍照键"),
    KEY_FOCUS("对焦键"),

    SWIPE_UP("屏幕手势 · 向上滑动"),
    SWIPE_DOWN("屏幕手势 · 向下滑动"),
    SWIPE_LEFT("屏幕手势 · 向左滑动"),
    SWIPE_RIGHT("屏幕手势 · 向右滑动"),
    TAP_LEFT("屏幕手势 · 点击屏幕左侧"),
    TAP_CENTER("屏幕手势 · 点击屏幕中间"),
    TAP_RIGHT("屏幕手势 · 点击屏幕右侧"),
    TAP_BOTTOM("屏幕手势 · 点击屏幕下方"),
    DOUBLE_TAP("屏幕手势 · 双击屏幕中间"),
    LONG_PRESS("屏幕手势 · 长按屏幕中间"),
    ;

    /** Android key code for the key-shaped actions, null for gestures. */
    val keyCode: Int?
        get() = when (this) {
            KEY_BACK -> 4
            KEY_HOME -> 3
            KEY_ENTER -> 66
            KEY_MENU -> 82
            KEY_APP_SWITCH -> 187
            KEY_DPAD_UP -> 19
            KEY_DPAD_DOWN -> 20
            KEY_DPAD_LEFT -> 21
            KEY_DPAD_RIGHT -> 22
            KEY_VOLUME_UP -> 24
            KEY_VOLUME_DOWN -> 25
            KEY_VOLUME_MUTE -> 164
            KEY_MEDIA_PLAY_PAUSE -> 85
            KEY_MEDIA_NEXT -> 87
            KEY_MEDIA_PREVIOUS -> 88
            KEY_MEDIA_REWIND -> 89
            KEY_MEDIA_FAST_FORWARD -> 90
            KEY_PAGE_UP -> 92
            KEY_PAGE_DOWN -> 93
            KEY_CAMERA -> 27
            KEY_FOCUS -> 80
            else -> null
        }
}

/** One app's bindings. */
data class AppProfile(
    val packageName: String,
    val label: String,
    val enabled: Boolean = true,
    val bindings: Map<ProfileTrigger, ProfileAction> = emptyMap(),
) {
    fun actionFor(trigger: ProfileTrigger): ProfileAction =
        bindings[trigger] ?: ProfileAction.NONE

    fun withBinding(trigger: ProfileTrigger, action: ProfileAction): AppProfile {
        val next = bindings.toMutableMap()
        if (action == ProfileAction.NONE) next.remove(trigger) else next[trigger] = action
        return copy(bindings = next)
    }
}

/**
 * Ready made bindings for the apps people actually use the remote with.
 *
 * Templates lean on Android's own keys wherever one exists, so they keep
 * working on any phone; the short-video like is the one case that has no key
 * equivalent and therefore uses a screen gesture.
 */
enum class ProfileTemplate(val label: String, val description: String) {
    NOVEL("小说阅读", "左右点击翻页，上下滑动滚动，按下确定"),
    SHORT_VIDEO("短视频", "上下滑动切换，按下双击点赞"),
    CAMERA("相机", "按下用快门键，音量键当快门"),
    BLANK("空白", "全部从“不设置”开始，自己配"),
    ;

    fun bindings(): Map<ProfileTrigger, ProfileAction> = when (this) {
        NOVEL -> mapOf(
            ProfileTrigger.TOUCH_LEFT to ProfileAction.TAP_LEFT,
            ProfileTrigger.TOUCH_RIGHT to ProfileAction.TAP_RIGHT,
            ProfileTrigger.TOUCH_UP to ProfileAction.SWIPE_UP,
            ProfileTrigger.TOUCH_DOWN to ProfileAction.SWIPE_DOWN,
            ProfileTrigger.TOUCH_PRESS to ProfileAction.KEY_ENTER,
        )

        SHORT_VIDEO -> mapOf(
            ProfileTrigger.TOUCH_UP to ProfileAction.SWIPE_UP,
            ProfileTrigger.TOUCH_DOWN to ProfileAction.SWIPE_DOWN,
            ProfileTrigger.TOUCH_LEFT to ProfileAction.SWIPE_LEFT,
            ProfileTrigger.TOUCH_RIGHT to ProfileAction.SWIPE_RIGHT,
            ProfileTrigger.TOUCH_PRESS to ProfileAction.DOUBLE_TAP,
            ProfileTrigger.VOLUME_UP to ProfileAction.KEY_VOLUME_UP,
            ProfileTrigger.VOLUME_DOWN to ProfileAction.KEY_VOLUME_DOWN,
        )

        CAMERA -> mapOf(
            ProfileTrigger.TOUCH_PRESS to ProfileAction.KEY_CAMERA,
            ProfileTrigger.VOLUME_UP to ProfileAction.KEY_VOLUME_UP,
            ProfileTrigger.VOLUME_DOWN to ProfileAction.KEY_VOLUME_DOWN,
        )

        BLANK -> emptyMap()
    }

    fun applyTo(profile: AppProfile): AppProfile = profile.copy(bindings = bindings())
}
