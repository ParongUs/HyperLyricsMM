package com.hyperlyrics.mm.xposed.expanded

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
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.hyperlyrics.mm.common.AppStringsProvider
import com.hyperlyrics.mm.lyrics.model.ActiveLyricSnapshot
import com.hyperlyrics.mm.lyrics.model.LyricLine
import com.hyperlyrics.mm.lyrics.model.LyricsState
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import com.hyperlyrics.mm.lyrics.sync.LyricsTimelineEngine
import com.hyperlyrics.mm.xposed.RuntimeSettingsHolder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Hardware-accelerated flowing synced lyrics view tailored for the right pane of
 * Xiaomi HyperOS Expanded Super Island in YouTube Music's signature style.
 *
 * Features:
 *  - Smooth critically-damped vertical spring scrolling at 60–120fps (auto-idles at 0% CPU)
 *  - Authentic YouTube Music typography hierarchy (bold luminous active line, soft muted context lines)
 *  - Top & bottom alpha edge fade masks (no abrupt clipping)
 *  - Pulsing 3-dot interlude indicator during instrumental breaks
 *  - Interactive vertical swipe-to-browse + tap-any-line-to-seek
 *  - Animated shimmer skeleton while loading & localized empty states
 */
class FlowingLyricsView(context: Context) : View(context) {

    var positionProvider: (() -> Long)? = null
    var onSeekRequested: ((Long) -> Unit)? = null
    var onRetryRequested: (() -> Unit)? = null

    private var lyricsState: LyricsState = LyricsState.Idle
    private var isPlaying: Boolean = false
    private var syncOffsetMs: Int = 0
    private var fontSizeSp: Float = 14.5f
    private var accentColor: Int = 0xFFFF0033.toInt()
    private var tapToSeekEnabled: Boolean = true
    private var showInterludeDots: Boolean = true

    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Paints
    private val activePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val inactivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x52FFFFFF
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8CFFFFFF.toInt()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fadeMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    // Cached static layouts for zero-allocation rendering
    private data class LineLayoutItem(
        val index: Int,
        val line: LyricLine,
        val activeLayout: StaticLayout,
        val inactiveLayout: StaticLayout,
        val topY: Float,
        val centerY: Float,
        val height: Float
    )

    private var cachedItems: List<LineLayoutItem> = emptyList()
    private var totalContentHeight: Float = 0f
    private var layoutDirty: Boolean = true

    // Per-line animated states (0f = inactive, 1f = fully active)
    private var lineFocusWeights = FloatArray(0)

    // Smooth camera spring state
    private var currentScrollY: Float = 0f
    private var targetScrollY: Float = 0f
    private var scrollVelocity: Float = 0f
    private var lastFrameTimeNanos: Long = 0L
    private var frameScheduled: Boolean = false

    // User manual scroll state
    private var isUserDragging: Boolean = false
    private var userScrollOverrideUntilMs: Long = 0L
    private var touchDownY: Float = 0f
    private var touchDownX: Float = 0f
    private var lastTouchY: Float = 0f
    private var hasDragged: Boolean = false

    // Latest sync snapshot
    private var lastSnapshot: ActiveLyricSnapshot = ActiveLyricSnapshot.EMPTY
    private var lastRenderedActiveIndex: Int = -2
    private var fadeShader: Shader? = null
    private val shimmerRect = RectF()
    private val layerRect = RectF()

    private val frameRunnable = Runnable {
        frameScheduled = false
        tickAnimationFrame()
    }

    init {
        isClickable = true
        isFocusable = false
        updateTextSizes()
    }

    fun updateStyle(
        fontSizeSp: Float,
        accentColor: Int,
        syncOffsetMs: Int,
        tapToSeek: Boolean,
        showInterludeDots: Boolean
    ) {
        val sizeChanged = abs(this.fontSizeSp - fontSizeSp) > 0.05f
        this.fontSizeSp = fontSizeSp.coerceIn(11f, 18f)
        this.accentColor = accentColor
        this.syncOffsetMs = syncOffsetMs
        this.tapToSeekEnabled = tapToSeek
        this.showInterludeDots = showInterludeDots
        if (sizeChanged) {
            updateTextSizes()
            layoutDirty = true
        }
        invalidate()
        ensureFrameLoopIfNeeded()
    }

    fun updateLyricsState(state: LyricsState) {
        if (state == lyricsState) return
        val previousLyrics = (lyricsState as? LyricsState.Ready)?.lyrics
        val nextLyrics = (state as? LyricsState.Ready)?.lyrics
        lyricsState = state
        if (previousLyrics !== nextLyrics) {
            layoutDirty = true
            userScrollOverrideUntilMs = 0L
            lastRenderedActiveIndex = -2
            if (nextLyrics != null) {
                lineFocusWeights = FloatArray(nextLyrics.lines.size)
            } else {
                lineFocusWeights = FloatArray(0)
            }
        }
        invalidate()
        ensureFrameLoopIfNeeded()
    }

    fun updatePlaybackState(playing: Boolean) {
        if (this.isPlaying == playing) return
        this.isPlaying = playing
        invalidate()
        ensureFrameLoopIfNeeded()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) layoutDirty = true
        if (w > 0 && h > 0) {
            layerRect.set(0f, 0f, w.toFloat(), h.toFloat())
            fadeShader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, 0.16f, 0.80f, 1f),
                Shader.TileMode.CLAMP
            )
            fadeMaskPaint.shader = fadeShader
        }
        ensureFrameLoopIfNeeded()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureFrameLoopIfNeeded()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameRunnable)
        frameScheduled = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            ensureFrameLoopIfNeeded()
        } else {
            removeCallbacks(frameRunnable)
            frameScheduled = false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val state = lyricsState
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                lastTouchY = event.y
                hasDragged = false
                if (state is LyricsState.Ready) {
                    isUserDragging = true
                    scrollVelocity = 0f
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastTouchY
                val totalDy = abs(event.y - touchDownY)
                val totalDx = abs(event.x - touchDownX)
                if (!hasDragged && totalDy > touchSlop && totalDy > totalDx) {
                    hasDragged = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (hasDragged && state is LyricsState.Ready) {
                    val maxScroll = (totalContentHeight - height * 0.5f).coerceAtLeast(0f)
                    val minScroll = -(height * 0.25f)
                    currentScrollY = (currentScrollY - dy).coerceIn(minScroll, maxScroll)
                    targetScrollY = currentScrollY
                    userScrollOverrideUntilMs = SystemClock.uptimeMillis() + USER_SCROLL_HOLD_MS
                    invalidate()
                }
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                isUserDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (hasDragged) {
                    userScrollOverrideUntilMs = SystemClock.uptimeMillis() + USER_SCROLL_HOLD_MS
                    ensureFrameLoopIfNeeded()
                } else {
                    handleTap(event.y)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isUserDragging = false
                hasDragged = false
                parent?.requestDisallowInterceptTouchEvent(false)
                ensureFrameLoopIfNeeded()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(touchY: Float) {
        when (val state = lyricsState) {
            is LyricsState.Ready -> {
                if (!tapToSeekEnabled || !state.lyrics.isSynced) return
                val contentY = touchY + currentScrollY
                val tapped = cachedItems.firstOrNull { item ->
                    contentY >= item.topY - dp(5f) && contentY <= item.topY + item.height + dp(5f)
                } ?: return
                if (!tapped.line.isInterlude) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    userScrollOverrideUntilMs = 0L
                    onSeekRequested?.invoke(tapped.line.startTimeMs)
                    ensureFrameLoopIfNeeded()
                }
            }

            is LyricsState.Unavailable -> {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onRetryRequested?.invoke()
            }

            else -> Unit
        }
    }

    private fun updateTextSizes() {
        val activePx = sp(fontSizeSp)
        val inactivePx = sp((fontSizeSp * 0.87f).coerceAtLeast(10f))
        activePaint.textSize = activePx
        inactivePaint.textSize = inactivePx
        footerPaint.textSize = sp(9.5f)
        statusPaint.textSize = sp(11.5f)
    }

    private fun ensureLayoutBuilt(lyrics: TrackLyrics) {
        if (!layoutDirty && cachedItems.size == lyrics.lines.size) return
        val availWidth = (width - paddingLeft - paddingRight - dp(4f).toInt()).coerceAtLeast(dp(40f).toInt())
        val lineGap = dp(9f)
        val interludeHeight = dp(20f)

        val items = ArrayList<LineLayoutItem>(lyrics.lines.size)
        var y = dp(8f)

        lyrics.lines.forEachIndexed { idx, line ->
            val displayText = if (line.isInterlude) "" else line.text
            val activeLayout = buildStaticLayout(displayText, activePaint, availWidth)
            val inactiveLayout = buildStaticLayout(displayText, inactivePaint, availWidth)
            val h = if (line.isInterlude) {
                interludeHeight
            } else {
                maxOf(activeLayout.height.toFloat(), inactiveLayout.height.toFloat())
            }
            items.add(
                LineLayoutItem(
                    index = idx,
                    line = line,
                    activeLayout = activeLayout,
                    inactiveLayout = inactiveLayout,
                    topY = y,
                    centerY = y + h * 0.5f,
                    height = h
                )
            )
            y += h + lineGap
        }

        cachedItems = items
        totalContentHeight = y + dp(24f)
        if (lineFocusWeights.size != lyrics.lines.size) {
            lineFocusWeights = FloatArray(lyrics.lines.size)
        }
        layoutDirty = false
    }

    private fun buildStaticLayout(text: String, paint: TextPaint, widthPx: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(dp(1.5f), 1.0f)
            .build()
    }

    private fun ensureFrameLoopIfNeeded() {
        if (!isAttachedToWindow || visibility != VISIBLE) return
        if (!frameScheduled) {
            frameScheduled = true
            lastFrameTimeNanos = System.nanoTime()
            postOnAnimation(frameRunnable)
        }
    }

    private fun tickAnimationFrame() {
        if (!isAttachedToWindow || visibility != VISIBLE) return

        val nowNanos = System.nanoTime()
        val dt = ((nowNanos - lastFrameTimeNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastFrameTimeNanos = nowNanos

        var needsMoreFrames = false

        when (val state = lyricsState) {
            is LyricsState.Loading -> {
                invalidate()
                needsMoreFrames = true
            }

            is LyricsState.Ready -> {
                if (width > 0 && height > 0) {
                    ensureLayoutBuilt(state.lyrics)
                    val posMs = positionProvider?.invoke() ?: 0L
                    val snapshot = LyricsTimelineEngine.resolve(
                        lyrics = state.lyrics,
                        rawPositionMs = posMs,
                        offsetMs = syncOffsetMs,
                        durationMs = state.lyrics.durationMs
                    )
                    lastSnapshot = snapshot

                    val opticalCenterY = height * 0.43f
                    val nowUptime = SystemClock.uptimeMillis()
                    val userOverriding = isUserDragging || nowUptime < userScrollOverrideUntilMs

                    if (!userOverriding && cachedItems.isNotEmpty()) {
                        val focusIdx = snapshot.activeIndex.coerceIn(0, cachedItems.lastIndex)
                        val item = cachedItems[focusIdx]
                        targetScrollY = item.centerY - opticalCenterY

                        // Snap immediately on first track load so it doesn't fly from 0
                        if (lastRenderedActiveIndex == -2) {
                            currentScrollY = targetScrollY
                            scrollVelocity = 0f
                        }
                        lastRenderedActiveIndex = snapshot.activeIndex

                        // Critically-damped spring physics
                        val stiffness = 145f
                        val damping = 23.5f
                        val displacement = targetScrollY - currentScrollY
                        val accel = displacement * stiffness - scrollVelocity * damping
                        scrollVelocity += accel * dt
                        currentScrollY += scrollVelocity * dt

                        if (abs(displacement) > 0.35f || abs(scrollVelocity) > 0.4f) {
                            needsMoreFrames = true
                        } else {
                            currentScrollY = targetScrollY
                            scrollVelocity = 0f
                        }
                    } else if (userOverriding) {
                        needsMoreFrames = true
                    }

                    // Smooth per-line focus weights
                    val focusFactor = 1f - exp(-11f * dt)
                    for (i in lineFocusWeights.indices) {
                        val goal = if (i == snapshot.activeIndex && !snapshot.isInterlude) 1f else 0f
                        val diff = goal - lineFocusWeights[i]
                        if (abs(diff) > 0.004f) {
                            lineFocusWeights[i] += diff * focusFactor
                            needsMoreFrames = true
                        } else {
                            lineFocusWeights[i] = goal
                        }
                    }

                    if (isPlaying) {
                        needsMoreFrames = true
                    }
                    invalidate()
                }
            }

            else -> Unit
        }

        if (needsMoreFrames) {
            frameScheduled = true
            postOnAnimation(frameRunnable)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return

        val strings = AppStringsProvider.resolve(RuntimeSettingsHolder.current.appLanguage)
        when (val state = lyricsState) {
            is LyricsState.Idle -> drawStatusState(canvas, strings.waitingForYtm)
            is LyricsState.Loading -> drawLoadingShimmer(canvas)
            is LyricsState.Unavailable -> drawStatusState(canvas, strings.lyricsUnavailable)
            is LyricsState.Ready -> drawFlowingLyrics(canvas, state.lyrics)
        }
    }

    private fun drawFlowingLyrics(canvas: Canvas, lyrics: TrackLyrics) {
        ensureLayoutBuilt(lyrics)
        if (cachedItems.isEmpty()) {
            val strings = AppStringsProvider.resolve(RuntimeSettingsHolder.current.appLanguage)
            drawStatusState(canvas, strings.noLyrics)
            return
        }

        val saveCount = canvas.saveLayer(layerRect, null)
        val contentLeft = paddingLeft.toFloat()

        val activeIdx = lastSnapshot.activeIndex
        val visibleTop = currentScrollY - dp(36f)
        val visibleBottom = currentScrollY + height + dp(36f)

        // Draw lead-in interlude dots before first lyric if applicable
        if (showInterludeDots && lastSnapshot.isInterlude && activeIdx < 0) {
            drawInterludeDots(
                canvas = canvas,
                startX = contentLeft,
                centerY = height * 0.43f,
                progress = lastSnapshot.interludeProgress
            )
        }

        for (item in cachedItems) {
            if (item.topY + item.height < visibleTop || item.topY > visibleBottom) continue

            val drawY = item.topY - currentScrollY
            val focus = lineFocusWeights.getOrElse(item.index) { 0f }

            if (item.line.isInterlude) {
                if (showInterludeDots && item.index == activeIdx) {
                    drawInterludeDots(
                        canvas = canvas,
                        startX = contentLeft,
                        centerY = drawY + item.height * 0.5f,
                        progress = lastSnapshot.interludeProgress
                    )
                }
                continue
            }

            val distance = item.index - activeIdx
            val baseAlpha = when {
                distance == 0 && !lastSnapshot.isInterlude -> 1.0f
                distance < 0 -> 0.30f // passed lines
                distance == 1 -> 0.56f // immediate next upcoming line
                distance == 2 -> 0.34f
                else -> 0.22f
            }
            val finalAlpha = (baseAlpha + (1f - baseAlpha) * focus).coerceIn(0.18f, 1f)
            val alphaInt = (finalAlpha * 255f).toInt().coerceIn(0, 255)

            canvas.save()
            canvas.translate(contentLeft, drawY)

            if (focus > 0.35f) {
                val scale = 0.96f + 0.04f * focus
                canvas.scale(scale, scale, 0f, item.height * 0.5f)
                activePaint.alpha = alphaInt
                val glowAlpha = (focus * 72f).toInt().coerceIn(0, 72)
                activePaint.setShadowLayer(
                    dp(6f) * focus,
                    0f,
                    0f,
                    Color.argb(glowAlpha, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                )
                item.activeLayout.draw(canvas)
                activePaint.clearShadowLayer()
            } else {
                inactivePaint.alpha = alphaInt
                item.inactiveLayout.draw(canvas)
            }
            canvas.restore()
        }

        // Authentic YouTube Music source attribution below last line
        val attribution = lyrics.sourceMessage?.takeIf { it.isNotBlank() } ?: "YouTube Music"
        val footerY = (cachedItems.lastOrNull()?.let { it.topY + it.height } ?: 0f) + dp(16f) - currentScrollY
        if (footerY > -dp(20f) && footerY < height + dp(20f)) {
            canvas.drawText(attribution, contentLeft, footerY, footerPaint)
        }

        // Apply soft top & bottom edge gradient mask
        if (fadeShader != null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadeMaskPaint)
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawInterludeDots(
        canvas: Canvas,
        startX: Float,
        centerY: Float,
        progress: Float
    ) {
        val radius = dp(3.4f)
        val spacing = dp(11.5f)
        val timeSec = SystemClock.uptimeMillis() / 1000f
        for (i in 0..2) {
            val threshold = i / 3f
            val fill = ((progress - threshold) * 3f).coerceIn(0f, 1f)
            val pulse = 0.5f + 0.5f * sin(timeSec * 3.6f - i * 0.9f)
            val alpha = ((0.28f + 0.52f * fill + 0.20f * pulse).coerceIn(0.25f, 1f) * 255f).toInt()
            val dotScale = 0.85f + 0.22f * pulse
            dotPaint.color = if (fill > 0.3f) {
                Color.argb(alpha, 255, 255, 255)
            } else {
                Color.argb(alpha, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            }
            canvas.drawCircle(startX + radius + i * spacing, centerY, radius * dotScale, dotPaint)
        }
    }

    private fun drawLoadingShimmer(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() % 1400L) / 1400f
        val baseY = height * 0.28f
        val barH = dp(10f)
        val gap = dp(12f)
        val widths = floatArrayOf(0.68f, 0.92f, 0.78f, 0.54f)
        val usableW = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(dp(40f))

        widths.forEachIndexed { idx, ratio ->
            val phase = (t - idx * 0.14f + 1f) % 1f
            val wave = 0.5f + 0.5f * sin(phase * Math.PI.toFloat() * 2f)
            val alpha = (26 + wave * 34).toInt()
            shimmerPaint.color = Color.argb(alpha, 255, 255, 255)
            val y = baseY + idx * (barH + gap)
            shimmerRect.set(
                paddingLeft.toFloat(),
                y,
                paddingLeft + usableW * ratio,
                y + barH
            )
            canvas.drawRoundRect(shimmerRect, barH * 0.5f, barH * 0.5f, shimmerPaint)
        }
    }

    private fun drawStatusState(canvas: Canvas, text: String) {
        val cx = width * 0.5f
        val cy = height * 0.52f
        canvas.drawText(text, cx, cy, statusPaint)
    }

    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        private const val USER_SCROLL_HOLD_MS = 2600L
    }
}
