package com.vrremote.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Draws the flat touchpad as a 256 x 256 field.
 *
 * The controller reports the touchpad as a raw byte pair, so this view stays
 * deliberately literal: no smoothing, no interpretation, just where the finger
 * is and where it has been.
 */
class TouchpadView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFF2C3742.toInt()
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF3D4A57.toInt()
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0xFF4C5C6B.toInt()
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x66FFC107.toInt()
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFC107.toInt()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7A8896.toInt()
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }

    var current: Pair<Int, Int>? = null
        set(value) {
            field = value
            invalidate()
        }

    var trail: List<Pair<Int, Int>> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val padding = 16f
        val size = min(width - padding * 2f, height - padding * 2f)
        if (size <= 0f) return
        val left = (width - size) / 2f
        val top = (height - size) / 2f

        canvas.drawRect(left, top, left + size, top + size, borderPaint)

        for (step in 1..3) {
            val offset = size * step / 4f
            canvas.drawLine(left + offset, top, left + offset, top + size, gridPaint)
            canvas.drawLine(left, top + offset, left + size, top + offset, gridPaint)
        }
        canvas.drawLine(left + size / 2f, top, left + size / 2f, top + size, axisPaint)
        canvas.drawLine(left, top + size / 2f, left + size, top + size / 2f, axisPaint)

        for ((index, point) in trail.withIndex()) {
            val radius = 2f + 5f * (index + 1) / trail.size
            canvas.drawCircle(
                left + size * point.first / 255f,
                top + size * point.second / 255f,
                radius,
                trailPaint,
            )
        }

        val point = current
        if (point == null) {
            canvas.drawText("未触摸", width / 2f, height / 2f, textPaint)
        } else {
            canvas.drawCircle(
                left + size * point.first / 255f,
                top + size * point.second / 255f,
                10f,
                dotPaint,
            )
        }
    }
}
