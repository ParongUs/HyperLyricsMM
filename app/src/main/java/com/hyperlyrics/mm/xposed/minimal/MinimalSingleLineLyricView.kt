package com.hyperlyrics.mm.xposed.minimal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Compact single-line lyric view injected into HyperOS's Minimal (Collapsed) Super Island pill.
 *
 * Features:
 *  - Smaller, clean single-line typography aligned to the camera cutout capsule
 *  - Smooth 220ms vertical crossfade-slide on line change
 *  - Gentle horizontal marquee when a line is wider than the pill, with soft horizontal edge fades
 *  - Adaptive width measurement capped by user-configured maxWidthDp
 */
class MinimalSingleLineLyricView(context: Context) : View(context) {

    var onMeasuredWidthHintChanged: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF5FFFFFF.toInt()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private var fontSizeSp: Float = 11.0f
    private var maxWidthPx: Int = dp(132f)

    private var currentText: String = ""
    private var previousText: String = ""
    private var transitionStartMs: Long = 0L
    private var lineSetTimeMs: Long = 0L

    private var currentTextWidth: Float = 0f
    private var previousTextWidth: Float = 0f
    private var lastReportedWidthPx: Int = 0
    private var marqueeOffsetPx: Float = 0f
    private var frameScheduled: Boolean = false
    private var isPlaying: Boolean = false

    private val layerRect = RectF()
    private var fadeShader: Shader? = null

    private val frameRunnable = Runnable {
        frameScheduled = false
        tickFrame()
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        applyTextSize()
    }

    fun configure(fontSizeSp: Float, maxWidthDp: Int) {
        val clampedSp = fontSizeSp.coerceIn(9f, 13.5f)
        val clampedMaxPx = dp(maxWidthDp.coerceIn(80, 200).toFloat())
        val changed = kotlin.math.abs(this.fontSizeSp - clampedSp) > 0.05f || this.maxWidthPx != clampedMaxPx
        this.fontSizeSp = clampedSp
        this.maxWidthPx = clampedMaxPx
        if (changed) {
            applyTextSize()
            currentTextWidth = measureTextWidth(currentText)
            previousTextWidth = measureTextWidth(previousText)
            requestLayout()
            invalidate()
        }
    }

    fun setPlaying(playing: Boolean) {
        if (this.isPlaying == playing) return
        this.isPlaying = playing
        if (playing) ensureFrameLoop()
    }

    fun setLyricLine(text: String) {
        val clean = text.trim()
        if (clean == currentText) return
        previousText = currentText
        previousTextWidth = currentTextWidth
        currentText = clean
        currentTextWidth = measureTextWidth(clean)
        val now = SystemClock.uptimeMillis()
        transitionStartMs = now
        lineSetTimeMs = now
        marqueeOffsetPx = 0f

        val desiredW = desiredWidthPx()
        if (kotlin.math.abs(desiredW - lastReportedWidthPx) > dp(4f)) {
            lastReportedWidthPx = desiredW
            requestLayout()
            onMeasuredWidthHintChanged?.invoke()
        }
        invalidate()
        ensureFrameLoop()
    }

    fun desiredWidthPx(): Int {
        if (currentText.isEmpty()) return 0
        val padH = dp(10f)
        return (currentTextWidth.roundToInt() + padH).coerceIn(dp(28f), maxWidthPx)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = desiredWidthPx()
        val desiredH = (textPaint.fontMetrics.run { bottom - top }).roundToInt() + dp(6f)

        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val finalW = when (wMode) {
            MeasureSpec.EXACTLY -> min(wSize, maxWidthPx)
            MeasureSpec.AT_MOST -> min(desiredW, min(wSize, maxWidthPx))
            else -> desiredW
        }.coerceAtLeast(0)

        val finalH = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(finalW, finalH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layerRect.set(0f, 0f, w.toFloat(), h.toFloat())
        if (w > 0) {
            val edgeFrac = (dp(8f).toFloat() / w.toFloat()).coerceIn(0.04f, 0.20f)
            fadeShader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, edgeFrac, 1f - edgeFrac, 1f),
                Shader.TileMode.CLAMP
            )
            fadePaint.shader = fadeShader
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureFrameLoop()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameRunnable)
        frameScheduled = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        if (currentText.isEmpty() && previousText.isEmpty()) return

        val now = SystemClock.uptimeMillis()
        val transProgress = ((now - transitionStartMs).toFloat() / TRANSITION_MS).coerceIn(0f, 1f)
        val eased = 1f - (1f - transProgress) * (1f - transProgress) * (1f - transProgress)

        val fm = textPaint.fontMetrics
        val baseY = (height - (fm.descent + fm.ascent)) * 0.5f
        val slideOffset = dp(6f).toFloat()
        val innerPad = dp(4f).toFloat()
        val innerWidth = (width - innerPad * 2f).coerceAtLeast(1f)
        val needsFade = currentTextWidth > innerWidth

        val saveCount = if (needsFade && fadeShader != null) {
            canvas.saveLayer(layerRect, null)
        } else {
            canvas.save()
        }

        // Outgoing previous line sliding smoothly upward
        if (transProgress < 1f && previousText.isNotEmpty()) {
            textPaint.alpha = ((1f - eased) * 245f).roundToInt().coerceIn(0, 255)
            canvas.drawText(previousText, innerPad, baseY - slideOffset * eased, textPaint)
        }

        // Incoming / active single line
        if (currentText.isNotEmpty()) {
            textPaint.alpha = (eased * 245f).roundToInt().coerceIn(0, 255)
            val overflow = (currentTextWidth - innerWidth).coerceAtLeast(0f)
            val drawX = if (overflow > 0f) {
                innerPad - marqueeOffsetPx.coerceIn(0f, overflow)
            } else {
                // Center gently inside capsule slot
                innerPad + (innerWidth - currentTextWidth) * 0.5f
            }
            val yOffset = if (transProgress < 1f) slideOffset * (1f - eased) else 0f
            canvas.drawText(currentText, drawX, baseY + yOffset, textPaint)
        }

        if (needsFade && fadeShader != null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)
        }
        canvas.restoreToCount(saveCount)
    }

    private fun tickFrame() {
        if (!isAttachedToWindow || visibility != VISIBLE) return
        val now = SystemClock.uptimeMillis()
        val inTransition = (now - transitionStartMs) < TRANSITION_MS

        val innerWidth = (width - dp(8f)).toFloat().coerceAtLeast(1f)
        val overflow = (currentTextWidth - innerWidth).coerceAtLeast(0f)
        val needsMarquee = isPlaying && overflow > dp(2f)

        if (needsMarquee) {
            val elapsedSinceSet = (now - lineSetTimeMs - MARQUEE_HOLD_MS).coerceAtLeast(0L)
            if (elapsedSinceSet > 0L) {
                val speedPxPerSec = dp(32f).toFloat()
                marqueeOffsetPx = ((elapsedSinceSet / 1000f) * speedPxPerSec).coerceAtMost(overflow)
            }
        } else {
            marqueeOffsetPx = 0f
        }

        val stillPanning = needsMarquee && marqueeOffsetPx < overflow
        if (inTransition || stillPanning || (needsMarquee && (now - lineSetTimeMs) <= MARQUEE_HOLD_MS)) {
            invalidate()
            frameScheduled = true
            postOnAnimation(frameRunnable)
        } else if (inTransition) {
            invalidate()
        }
    }

    private fun ensureFrameLoop() {
        if (!isAttachedToWindow || visibility != VISIBLE) return
        if (!frameScheduled) {
            frameScheduled = true
            postOnAnimation(frameRunnable)
        }
    }

    private fun applyTextSize() {
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            fontSizeSp,
            resources.displayMetrics
        )
    }

    private fun measureTextWidth(text: String): Float =
        if (text.isEmpty()) 0f else textPaint.measureText(text)

    private fun dp(value: Float): Int = (value * density).roundToInt()

    companion object {
        private const val TRANSITION_MS = 220f
        private const val MARQUEE_HOLD_MS = 750L
    }
}
