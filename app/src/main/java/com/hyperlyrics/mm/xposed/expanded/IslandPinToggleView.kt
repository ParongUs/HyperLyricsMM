package com.hyperlyrics.mm.xposed.expanded

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Compact hardware-drawn 1-tap Pin toggle badge mounted at the top-right corner of the
 * Expanded Hyper Island card. Draws cleanly via Canvas with zero host-resource dependencies:
 *  - Unpinned: subtle frosted glass circle with a 45° tilted push-pin outline
 *  - Pinned: glowing accent circle with an upright locked push-pin
 */
class IslandPinToggleView(context: Context) : View(context) {

    var onPinToggleRequested: (() -> Unit)? = null

    private var pinned: Boolean = false
    private var accentColor: Int = 0xFFFF0033.toInt()
    private val density = context.resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.25f * density
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.45f * density
    }
    private val pinPath = Path()

    init {
        isClickable = true
        isFocusable = false
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onPinToggleRequested?.invoke()
        }
    }

    fun updateState(isPinned: Boolean, accentColor: Int) {
        if (this.pinned == isPinned && this.accentColor == accentColor) return
        this.pinned = isPinned
        this.accentColor = accentColor
        contentDescription = if (isPinned) "Unpin expanded island" else "Pin expanded island"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w * 0.5f
        val cy = h * 0.5f
        val radius = (minOf(w, h) * 0.5f) - 1.2f * density

        if (pinned) {
            bgPaint.color = Color.argb(
                225,
                Color.red(accentColor),
                Color.green(accentColor),
                Color.blue(accentColor)
            )
            ringPaint.color = 0x80FFFFFF.toInt()
            pinPaint.color = Color.WHITE
            needlePaint.color = Color.WHITE
        } else {
            bgPaint.color = 0x29FFFFFF
            ringPaint.color = 0x38FFFFFF
            pinPaint.color = 0xC8FFFFFF.toInt()
            needlePaint.color = 0xC8FFFFFF.toInt()
        }

        canvas.drawCircle(cx, cy, radius, bgPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // Draw crisp minimalist push-pin centered inside the pill
        val unit = radius * 0.14f
        canvas.save()
        canvas.translate(cx, cy)
        if (!pinned) {
            canvas.rotate(40f)
        }

        // Pin head + flange
        pinPath.reset()
        pinPath.moveTo(-2.1f * unit, -3.5f * unit)
        pinPath.lineTo(2.1f * unit, -3.5f * unit)
        pinPath.lineTo(1.5f * unit, -0.4f * unit)
        pinPath.lineTo(3.0f * unit, 1.1f * unit)
        pinPath.lineTo(-3.0f * unit, 1.1f * unit)
        pinPath.lineTo(-1.5f * unit, -0.4f * unit)
        pinPath.close()

        canvas.drawPath(pinPath, pinPaint)
        // Pin needle
        canvas.drawLine(0f, 1.1f * unit, 0f, 4.3f * unit, needlePaint)
        canvas.restore()
    }
}
