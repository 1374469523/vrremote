package com.vrremote.controller

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * The little bar that appears when the mode changes.
 *
 * It is drawn by the app itself (an overlay window) instead of using a toast:
 * a toast is rendered by SystemUI on current Android versions, and at least one
 * Samsung DeX hook rewrites the text on the way through.
 */
class ModeHintOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null

    private val dismiss = Runnable { hide() }

    /** @return true when the hint was shown. */
    fun show(text: String): Boolean {
        hide()
        val density = context.resources.displayMetrics.density
        val label = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(
                (density * 26).toInt(),
                (density * 14).toInt(),
                (density * 26).toInt(),
                (density * 14).toInt(),
            )
            background = GradientDrawable().apply {
                cornerRadius = density * 24f
                setColor(0xE01B1D21.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (density * 96).toInt()
        }
        return try {
            windowManager.addView(label, params)
            view = label
            handler.postDelayed(dismiss, HINT_DURATION_MS)
            true
        } catch (error: Exception) {
            // Overlay permission missing: the caller falls back to a toast.
            false
        }
    }

    fun hide() {
        handler.removeCallbacks(dismiss)
        val current = view ?: return
        view = null
        try {
            windowManager.removeView(current)
        } catch (ignored: Exception) {
        }
    }

    private companion object {
        const val HINT_DURATION_MS = 1_500L
    }
}
