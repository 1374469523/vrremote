package com.vrremote.controller.remote

/** What one foreground lookup means for the app the profile mode follows. */
data class ForegroundUpdate(val packageName: String?, val changed: Boolean)

/**
 * Decides how a foreground lookup changes the tracked package.
 *
 * An unreadable lookup clears the value instead of keeping the previous one:
 * "unknown" has to mean "do nothing". Keeping the last app would fire its
 * bindings inside whatever app the user switched to, which looks like the remote
 * pressing the wrong button with no explanation.
 */
fun foregroundUpdate(previous: String?, read: String?): ForegroundUpdate =
    ForegroundUpdate(packageName = read, changed = read != previous)
