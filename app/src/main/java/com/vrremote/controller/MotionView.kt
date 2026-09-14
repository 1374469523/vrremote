package com.vrremote.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Live view of the controller's motion data: a bubble level driven by the
 * accelerometer plus signed bars for the three gyroscope axes.
 *
 * Values come straight from the 20 byte report, so nothing here depends on the
 * Huawei VR runtime.
 */
class MotionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        /** Full scale of the accelerometer decode, in g. */
        const val ACC_FULL_SCALE = 16f
        /** Full scale of the gyroscope decode, in degrees per second. */
        const val GYRO_FULL_SCALE = 2000f
        const val BAR_COUNT = 3
    }

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#FF3A434F")
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#FF2E3742")
    }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF4CAF50")
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF64B5F6")
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9AA7B4")
        textSize = 22f
    }

    var accX: Float = 0f
        set(value) {
            field = value
            invalidate()
        }
    var accY: Float = 0f
        set(value) {
            field = value
            invalidate()
        }
    var gyroX: Float = 0f
        set(value) {
            field = value
            invalidate()
        }
    var gyroY: Float = 0f
        set(value) {
            field = value
            invalidate()
        }
    var gyroZ: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        canvas.drawRect(0f, 0f, width - 1f, height - 1f, border)
        label.textSize = min(26f, height / 16f)

        // Bubble level: accelerometer tilt inside a circle.
        val levelRadius = min(width, height) * 0.34f
        val levelCx = width / 2f
        val levelCy = levelRadius + label.textSize * 1.6f
        canvas.drawCircle(levelCx, levelCy, levelRadius, grid)
        canvas.drawLine(levelCx - levelRadius, levelCy, levelCx + levelRadius, levelCy, grid)
        canvas.drawLine(levelCx, levelCy - levelRadius, levelCx, levelCy + levelRadius, grid)

        val nx = (accX / ACC_FULL_SCALE).coerceIn(-1f, 1f)
        val ny = (accY / ACC_FULL_SCALE).coerceIn(-1f, 1f)
        val dotX = levelCx + nx * levelRadius
        val dotY = levelCy + ny * levelRadius
        canvas.drawCircle(dotX, dotY, min(levelRadius, 60f) * 0.16f + 6f, bubble)
        canvas.drawText(
            "体感  x${format(nx)} y${format(ny)}",
            levelCx - levelRadius,
            levelCy - levelRadius - label.textSize * 0.5f,
            label,
        )

        // Gyroscope bars, one per axis, drawn from the vertical centre line.
        val barsTop = levelCy + levelRadius + label.textSize
        val barsBottom = height - label.textSize * 0.6f
        val barsHeight = (barsBottom - barsTop).coerceAtLeast(10f)
        val rowHeight = barsHeight / BAR_COUNT
        val values = floatArrayOf(gyroX, gyroY, gyroZ)
        val names = arrayOf("gx", "gy", "gz")
        for (index in 0 until BAR_COUNT) {
            val rowTop = barsTop + index * rowHeight
            val rowCenter = rowTop + rowHeight / 2f
            canvas.drawLine(label.textSize * 1.6f, rowCenter, width - 8f, rowCenter, grid)
            canvas.drawText(names[index], 4f, rowCenter + label.textSize * 0.35f, label)

            val fraction = (values[index] / GYRO_FULL_SCALE).coerceIn(-1f, 1f)
            val trackLeft = label.textSize * 1.6f
            val trackRight = width - 8f
            val trackCenter = (trackLeft + trackRight) / 2f
            val half = (trackRight - trackLeft) / 2f
            val thickness = rowHeight * 0.42f
            val rect = RectF(
                min(trackCenter, trackCenter + fraction * half),
                rowCenter - thickness / 2f,
                maxOf(trackCenter, trackCenter + fraction * half),
                rowCenter + thickness / 2f,
            )
            if (abs(fraction) > 0.01f) canvas.drawRect(rect, bar)
        }
    }

    private fun format(value: Float): String = "%+.2f".format(value)
}
