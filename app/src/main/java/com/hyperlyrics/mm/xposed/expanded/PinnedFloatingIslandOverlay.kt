package com.hyperlyrics.mm.xposed.expanded

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hyperlyrics.mm.common.ExpandedThemeStyle
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.common.ModuleSettings
import com.hyperlyrics.mm.lyrics.model.LyricsState
import com.hyperlyrics.mm.xposed.RuntimeSettingsHolder
import com.hyperlyrics.mm.xposed.session.YouTubeMusicSessionTracker
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Real SystemUI non-blocking floating Expanded Hyper Island window.
 *
 * Unlike HyperOS's native expanded island window (which Xiaomi's global touch monitor
 * collapses on any tap outside the card), this window is sized strictly to the expanded
 * card bounds and uses `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` without
 * `FLAG_WATCH_OUTSIDE_TOUCH` or `FLAG_DIM_BEHIND`.
 *
 * Result:
 *  - Every tap, scroll, swipe, and keyboard keystroke outside the card goes straight to
 *    the app underneath.
 *  - The expanded card stays open with flowing 60-120fps lyrics and working media controls
 *    until the user explicitly taps the glowing Pin/Unpin badge at the top-right corner.
 *  - Dragging the top-left header vertically lets the user slide the card up/down if it
 *    ever overlaps an app's top toolbar.
 */
internal object PinnedFloatingIslandOverlay {
    private const val TAG = "PinnedIslandOverlay"
    private const val TYPE_STATUS_BAR_ADDITIONAL = 2041
    private const val TYPE_NAVIGATION_BAR_PANEL = 2024

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isShowing: Boolean = false
        private set

    private var windowManager: WindowManager? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var rootCard: FrameLayout? = null
    private var backdropView: YtmAmbientBackdropView? = null
    private var leftPane: LinearLayout? = null
    private var dividerView: View? = null
    private var rightPane: FrameLayout? = null
    private var albumImage: ImageView? = null
    private var titleText: TextView? = null
    private var artistText: TextView? = null
    private var progressBar: PinnedProgressBarView? = null
    private var elapsedText: TextView? = null
    private var totalText: TextView? = null
    private var prevBtn: MediaGlyphButton? = null
    private var playPauseBtn: MediaGlyphButton? = null
    private var nextBtn: MediaGlyphButton? = null
    private var lyricsView: FlowingLyricsView? = null
    private var pinToggleView: IslandPinToggleView? = null

    private var currentPalette: YtmCardPalette = YtmCardPalette.DEFAULT_DYNAMIC
    private var currentArtworkBitmap: Bitmap? = null
    private var currentArtworkDrawable: Drawable? = null
    private var onUnpinRequested: (() -> Unit)? = null
    private var tickerRunning = false
    private var sessionListenerRegistered = false

    private val progressTicker = object : Runnable {
        override fun run() {
            if (!isShowing || !tickerRunning) return
            refreshProgressAndTime()
            mainHandler.postDelayed(this, if (YouTubeMusicSessionTracker.isPlaying) 250L else 1000L)
        }
    }

    fun show(
        context: Context,
        anchorRect: Rect?,
        palette: YtmCardPalette,
        artworkDrawable: Drawable?,
        artworkBitmap: Bitmap?,
        settings: ModuleSettings,
        onUnpin: () -> Unit
    ) {
        mainHandler.post {
            showOnMain(
                context = context.applicationContext ?: context,
                anchorRect = anchorRect,
                palette = palette,
                artworkDrawable = artworkDrawable,
                artworkBitmap = artworkBitmap,
                settings = settings,
                onUnpin = onUnpin
            )
        }
    }

    fun updateVisuals(
        palette: YtmCardPalette? = null,
        artworkDrawable: Drawable? = null,
        artworkBitmap: Bitmap? = null,
        settings: ModuleSettings = RuntimeSettingsHolder.current
    ) {
        mainHandler.post {
            if (!isShowing) return@post
            if (palette != null) currentPalette = palette
            if (artworkBitmap != null) currentArtworkBitmap = artworkBitmap
            if (artworkDrawable != null) currentArtworkDrawable = artworkDrawable
            applyStateToViews(settings)
        }
    }

    fun dismiss() {
        mainHandler.post { dismissOnMain() }
    }

    private fun showOnMain(
        context: Context,
        anchorRect: Rect?,
        palette: YtmCardPalette,
        artworkDrawable: Drawable?,
        artworkBitmap: Bitmap?,
        settings: ModuleSettings,
        onUnpin: () -> Unit
    ) {
        currentPalette = palette
        if (artworkBitmap != null) currentArtworkBitmap = artworkBitmap
        if (artworkDrawable != null) currentArtworkDrawable = artworkDrawable
        onUnpinRequested = onUnpin

        ensureSessionListeners()

        val dm = context.resources.displayMetrics
        val cardW = anchorRect?.width()?.takeIf { it > dp(context, 240f) }
            ?: (dm.widthPixels - dp(context, 28f)).coerceAtLeast(dp(context, 310f))
        val cardH = anchorRect?.height()?.takeIf { it in dp(context, 136f)..dp(context, 230f) }
            ?: dp(context, 168f)
        val minTopY = resolveStatusBarHeight(context) + dp(context, 8f)
        val topY = anchorRect?.top?.takeIf { it >= minTopY && it < dm.heightPixels / 2 }
            ?: minTopY

        if (isShowing && rootCard != null) {
            applyStateToViews(settings)
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val card = buildCardHierarchy(context, cardW, cardH, settings)

        val candidateTypes = intArrayOf(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            TYPE_STATUS_BAR_ADDITIONAL,
            TYPE_NAVIGATION_BAR_PANEL
        )

        var addedParams: WindowManager.LayoutParams? = null
        for (winType in candidateTypes) {
            val lp = WindowManager.LayoutParams(
                cardW,
                cardH,
                winType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = topY
                title = "HyperLyricsMM-PinnedIsland"
            }
            val added = runCatching { wm.addView(card, lp) }.isSuccess
            if (added) {
                addedParams = lp
                ModuleLogger.i(TAG, "Pinned floating expanded island added (type=$winType, ${cardW}x${cardH} at y=$topY)")
                break
            }
        }

        if (addedParams == null) {
            ModuleLogger.w(TAG, "Failed adding pinned floating island overlay across all window types")
            return
        }

        windowManager = wm
        windowParams = addedParams
        rootCard = card
        isShowing = true

        applyStateToViews(settings)

        // Subtle crisp pop-in animation
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(170L)
            .start()

        startProgressTicker()
    }

    private fun dismissOnMain() {
        stopProgressTicker()
        val card = rootCard ?: run {
            isShowing = false
            return
        }
        val wm = windowManager
        rootCard = null
        windowParams = null
        isShowing = false
        clearViewRefs()

        if (wm != null && card.isAttachedToWindow) {
            runCatching { wm.removeViewImmediate(card) }
                .onFailure { runCatching { wm.removeView(card) } }
        }
        ModuleLogger.i(TAG, "Pinned floating expanded island dismissed")
    }

    private fun buildCardHierarchy(
        context: Context,
        cardW: Int,
        cardH: Int,
        settings: ModuleSettings
    ): FrameLayout {
        val cornerRadius = dp(context, 26f).toFloat()

        val card = FrameLayout(context).apply {
            elevation = dp(context, 14f).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                }
            }
            clipToOutline = true
        }

        // 1. Dynamic YouTube Music / Glass backdrop
        val backdrop = YtmAmbientBackdropView(context)
        card.addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Subtle crisp card hairline rim
        val rimView = View(context).apply {
            isClickable = false
            isFocusable = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setCornerRadius(cornerRadius)
                setColor(Color.TRANSPARENT)
                setStroke(dp(context, 1f).coerceAtLeast(1), 0x28FFFFFF)
            }
        }
        card.addView(rimView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 2. Horizontal split row: Left compact player + hairline divider + Right flowing lyrics
        val splitRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(splitRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val leftW = (cardW * settings.leftPaneFraction).roundToInt()
            .coerceIn(dp(context, 134f), cardW - dp(context, 140f))

        // Left pane
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12f), dp(context, 12f), dp(context, 9f), dp(context, 11f))
        }
        splitRow.addView(left, LinearLayout.LayoutParams(leftW, ViewGroup.LayoutParams.MATCH_PARENT))

        // Left Top Header (Album art + Title/Artist) — supports vertical drag to reposition card
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        attachVerticalDragHandler(headerRow, context)
        left.addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val coverSize = dp(context, 44f)
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFFF0033.toInt(), 0xFF3E0F20.toInt())
            ).apply {
                setCornerRadius(dp(context, 10f).toFloat())
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(context, 10f).toFloat())
                }
            }
            clipToOutline = true
        }
        headerRow.addView(cover, LinearLayout.LayoutParams(coverSize, coverSize))

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 8f), 0, 0, 0)
        }
        headerRow.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val fadeLen = dp(context, 10f)
        val title = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.6f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(fadeLen)
            isSelected = true
        }
        val artist = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.6f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(0xB8FFFFFF.toInt())
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(fadeLen)
            isSelected = true
        }
        textCol.addView(title)
        textCol.addView(artist)

        // Spacer
        left.addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Scrubbable Progress Bar
        val prog = PinnedProgressBarView(context).apply {
            onSeekFraction = { frac ->
                val dur = YouTubeMusicSessionTracker.currentTrack?.durationMs
                    ?: (YouTubeMusicSessionTracker.currentLyricsState as? LyricsState.Ready)?.lyrics?.durationMs
                    ?: 0L
                if (dur > 0L) {
                    YouTubeMusicSessionTracker.seekTo((dur * frac).toLong())
                    refreshProgressAndTime()
                }
            }
        }
        left.addView(prog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 22f)))

        // Elapsed & Total timestamps
        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val elapsed = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            fontFeatureSettings = "tnum"
            setTextColor(0x99FFFFFF.toInt())
            text = "0:00"
        }
        val total = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            fontFeatureSettings = "tnum"
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.END
            text = "0:00"
        }
        timeRow.addView(elapsed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(total, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        left.addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = -dp(context, 3f)
        })

        // Spacer
        left.addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Media Transport Controls (Prev / Play-Pause / Next)
        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = MediaGlyphButton(context, MediaGlyph.PREV).apply {
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                YouTubeMusicSessionTracker.skipToPrevious()
            }
        }
        val playPause = MediaGlyphButton(context, MediaGlyph.PAUSE).apply {
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                YouTubeMusicSessionTracker.togglePlayPause()
            }
        }
        val next = MediaGlyphButton(context, MediaGlyph.NEXT).apply {
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                YouTubeMusicSessionTracker.skipToNext()
            }
        }
        controlsRow.addView(prev, LinearLayout.LayoutParams(dp(context, 36f), dp(context, 36f)))
        controlsRow.addView(View(context), LinearLayout.LayoutParams(dp(context, 10f), 1))
        controlsRow.addView(playPause, LinearLayout.LayoutParams(dp(context, 40f), dp(context, 40f)))
        controlsRow.addView(View(context), LinearLayout.LayoutParams(dp(context, 10f), 1))
        controlsRow.addView(next, LinearLayout.LayoutParams(dp(context, 36f), dp(context, 36f)))
        left.addView(controlsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Center hairline divider
        val div = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0x28FFFFFF, 0x28FFFFFF, Color.TRANSPARENT)
            )
        }
        splitRow.addView(
            div,
            LinearLayout.LayoutParams(dp(context, 1f).coerceAtLeast(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(context, 14f)
                bottomMargin = dp(context, 14f)
            }
        )

        // Right Pane: FlowingLyricsView
        val right = FrameLayout(context).apply {
            setPadding(dp(context, 9f), dp(context, 6f), dp(context, 12f), dp(context, 6f))
        }
        splitRow.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val lyrics = FlowingLyricsView(context).apply {
            positionProvider = { YouTubeMusicSessionTracker.currentPositionMs() }
            onSeekRequested = { targetMs -> YouTubeMusicSessionTracker.seekTo(targetMs) }
            onRetryRequested = { YouTubeMusicSessionTracker.forceRetryCurrentTrack() }
        }
        right.addView(lyrics, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top-right glowing Pin/Unpin toggle badge (1-tap closes pinned floating card)
        val pinBtnSize = dp(context, 24f)
        val pinBtn = IslandPinToggleView(context).apply {
            elevation = dp(context, 6f).toFloat()
            onPinToggleRequested = {
                onUnpinRequested?.invoke()
            }
        }
        card.addView(
            pinBtn,
            FrameLayout.LayoutParams(pinBtnSize, pinBtnSize, Gravity.TOP or Gravity.END).apply {
                marginEnd = dp(context, 9f)
                topMargin = dp(context, 8f)
            }
        )

        backdropView = backdrop
        leftPane = left
        dividerView = div
        rightPane = right
        albumImage = cover
        titleText = title
        artistText = artist
        progressBar = prog
        elapsedText = elapsed
        totalText = total
        prevBtn = prev
        playPauseBtn = playPause
        nextBtn = next
        lyricsView = lyrics
        pinToggleView = pinBtn

        return card
    }

    private fun attachVerticalDragHandler(handleView: View, context: Context) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val minTopY = resolveStatusBarHeight(context) + dp(context, 2f)
        val maxTopY = (context.resources.displayMetrics.heightPixels * 0.65f).roundToInt()
        var startRawY = 0f
        var startWinY = 0
        var dragging = false

        handleView.setOnTouchListener { _, event ->
            val lp = windowParams ?: return@setOnTouchListener false
            val card = rootCard ?: return@setOnTouchListener false
            val wm = windowManager ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawY = event.rawY
                    startWinY = lp.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - startRawY
                    if (!dragging && abs(dy) > touchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        val nextY = (startWinY + dy.roundToInt()).coerceIn(minTopY, maxTopY)
                        if (lp.y != nextY) {
                            lp.y = nextY
                            runCatching { wm.updateViewLayout(card, lp) }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun applyStateToViews(settings: ModuleSettings) {
        val card = rootCard ?: return
        val ctx = card.context

        // Update split width smoothly if user adjusts split ratio live
        val cardW = windowParams?.width ?: card.width
        if (cardW > 0) {
            val leftW = (cardW * settings.leftPaneFraction).roundToInt()
                .coerceIn(dp(ctx, 134f), cardW - dp(ctx, 140f))
            leftPane?.layoutParams?.let { lp ->
                if (lp.width != leftW) {
                    lp.width = leftW
                    leftPane?.layoutParams = lp
                }
            }
        }

        // Theme backdrop
        val effectivePalette = when (settings.expandedThemeStyle) {
            ExpandedThemeStyle.YTM_PURE_DARK -> YtmCardPalette.PURE_DARK
            ExpandedThemeStyle.HYPEROS_NATIVE -> YtmCardPalette(
                bgStart = 0xEE1D2028.toInt(),
                bgEnd = 0xEE0D0F14.toInt(),
                radialGlow = 0x22FFFFFF,
                accent = currentPalette.accent,
                progressActive = currentPalette.progressActive,
                progressTrack = 0x38FFFFFF
            )
            ExpandedThemeStyle.YTM_DYNAMIC -> currentPalette
        }
        backdropView?.setPalette(effectivePalette, animate = true)

        // Track metadata & artwork
        val track = YouTubeMusicSessionTracker.currentTrack
        titleText?.text = track?.title?.ifBlank { "HyperLyricsMM" } ?: "HyperLyricsMM"
        artistText?.text = track?.artist.orEmpty()

        val liveBitmap = YouTubeMusicSessionTracker.currentArtworkBitmap() ?: currentArtworkBitmap
        when {
            liveBitmap != null && !liveBitmap.isRecycled -> albumImage?.setImageBitmap(liveBitmap)
            currentArtworkDrawable != null -> albumImage?.setImageDrawable(currentArtworkDrawable)
        }

        // Play/Pause glyph & accent styling
        val isPlaying = YouTubeMusicSessionTracker.isPlaying
        playPauseBtn?.setGlyph(if (isPlaying) MediaGlyph.PAUSE else MediaGlyph.PLAY)
        playPauseBtn?.setCircleFill(0x28FFFFFF, Color.argb(72, Color.red(effectivePalette.accent), Color.green(effectivePalette.accent), Color.blue(effectivePalette.accent)))
        progressBar?.setColors(effectivePalette.progressActive, effectivePalette.progressTrack)
        refreshProgressAndTime()

        // Lyrics
        lyricsView?.apply {
            updateStyle(
                fontSizeSp = settings.expandedFontSizeSp,
                accentColor = effectivePalette.accent,
                syncOffsetMs = settings.syncOffsetMs,
                tapToSeek = settings.tapLyricToSeek,
                showInterludeDots = settings.showInterludeDots
            )
            updateLyricsState(YouTubeMusicSessionTracker.currentLyricsState)
            updatePlaybackState(isPlaying)
        }

        // Pin button stays lit to indicate active pinned lock; tap to unpin/dismiss
        pinToggleView?.updateState(isPinned = true, accentColor = effectivePalette.accent)
    }

    private fun refreshProgressAndTime() {
        val pos = YouTubeMusicSessionTracker.currentPositionMs()
        val dur = YouTubeMusicSessionTracker.currentTrack?.durationMs
            ?.takeIf { it > 0L }
            ?: (YouTubeMusicSessionTracker.currentLyricsState as? LyricsState.Ready)?.lyrics?.durationMs
            ?: 0L
        elapsedText?.text = formatTime(pos)
        totalText?.text = formatTime(dur)
        val frac = if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
        progressBar?.setProgress(frac)
    }

    private fun ensureSessionListeners() {
        if (sessionListenerRegistered) return
        sessionListenerRegistered = true

        RuntimeSettingsHolder.addListener { updated ->
            if (!updated.masterEnabled || !updated.expandedLyricsEnabled) {
                dismiss()
            } else if (isShowing) {
                updateVisuals(settings = updated)
            }
        }

        YouTubeMusicSessionTracker.addListener(object : YouTubeMusicSessionTracker.Listener {
            override fun onTrackChanged(track: YouTubeMusicSessionTracker.TrackInfo?) {
                if (!isShowing) return
                if (track == null) {
                    dismiss()
                    return
                }
                val bmp = YouTubeMusicSessionTracker.currentArtworkBitmap()
                if (bmp != null) {
                    currentArtworkBitmap = bmp
                    val ctx = rootCard?.context
                    if (ctx != null) {
                        val s = RuntimeSettingsHolder.current
                        currentPalette = YtmPaletteExtractor.extract(
                            drawable = BitmapDrawable(ctx.resources, bmp),
                            themeStyle = s.expandedThemeStyle,
                            accentMode = s.accentColorMode
                        )
                    }
                }
                updateVisuals(settings = RuntimeSettingsHolder.current)
            }

            override fun onLyricsStateChanged(state: LyricsState) {
                if (!isShowing) return
                lyricsView?.updateLyricsState(state)
            }

            override fun onPlaybackStateChanged(isPlaying: Boolean, positionMs: Long, speed: Float) {
                if (!isShowing) return
                playPauseBtn?.setGlyph(if (isPlaying) MediaGlyph.PAUSE else MediaGlyph.PLAY)
                lyricsView?.updatePlaybackState(isPlaying)
                refreshProgressAndTime()
            }
        })
    }

    private fun startProgressTicker() {
        stopProgressTicker()
        tickerRunning = true
        mainHandler.post(progressTicker)
    }

    private fun stopProgressTicker() {
        tickerRunning = false
        mainHandler.removeCallbacks(progressTicker)
    }

    private fun clearViewRefs() {
        backdropView = null
        leftPane = null
        dividerView = null
        rightPane = null
        albumImage = null
        titleText = null
        artistText = null
        progressBar = null
        elapsedText = null
        totalText = null
        prevBtn = null
        playPauseBtn = null
        nextBtn = null
        lyricsView = null
        pinToggleView = null
    }

    private fun resolveStatusBarHeight(context: Context): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else dp(context, 36f)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        return "%02d:%02d".format(totalSec / 60L, totalSec % 60L)
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private enum class MediaGlyph { PREV, PLAY, PAUSE, NEXT }

    private class MediaGlyphButton(
        context: Context,
        private var glyph: MediaGlyph
    ) : View(context) {
        private val density = context.resources.displayMetrics.density
        private var circleFillColor: Int = Color.TRANSPARENT
        private var circleStrokeColor: Int = Color.TRANSPARENT

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f * density
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xF2FFFFFF.toInt()
        }
        private val path = Path()
        private val barRect = RectF()

        init {
            isClickable = true
            isFocusable = false
        }

        fun setGlyph(next: MediaGlyph) {
            if (glyph == next) return
            glyph = next
            invalidate()
        }

        fun setCircleFill(fill: Int, stroke: Int) {
            circleFillColor = fill
            circleStrokeColor = stroke
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w * 0.5f
            val cy = h * 0.5f
            val r = minOf(w, h) * 0.48f

            if (circleFillColor != Color.TRANSPARENT) {
                circlePaint.color = circleFillColor
                canvas.drawCircle(cx, cy, r, circlePaint)
            }
            if (circleStrokeColor != Color.TRANSPARENT) {
                strokePaint.color = circleStrokeColor
                canvas.drawCircle(cx, cy, r - 0.5f * density, strokePaint)
            }

            val s = minOf(w, h) * 0.24f
            when (glyph) {
                MediaGlyph.PLAY -> {
                    path.reset()
                    path.moveTo(cx - s * 0.65f, cy - s)
                    path.lineTo(cx + s * 1.0f, cy)
                    path.lineTo(cx - s * 0.65f, cy + s)
                    path.close()
                    canvas.drawPath(path, iconPaint)
                }

                MediaGlyph.PAUSE -> {
                    val barW = s * 0.54f
                    val gap = s * 0.46f
                    val corner = 1.4f * density
                    barRect.set(cx - gap * 0.5f - barW, cy - s * 0.95f, cx - gap * 0.5f, cy + s * 0.95f)
                    canvas.drawRoundRect(barRect, corner, corner, iconPaint)
                    barRect.set(cx + gap * 0.5f, cy - s * 0.95f, cx + gap * 0.5f + barW, cy + s * 0.95f)
                    canvas.drawRoundRect(barRect, corner, corner, iconPaint)
                }

                MediaGlyph.PREV -> {
                    val barW = s * 0.30f
                    barRect.set(cx - s * 0.95f, cy - s * 0.85f, cx - s * 0.95f + barW, cy + s * 0.85f)
                    canvas.drawRoundRect(barRect, 1f * density, 1f * density, iconPaint)
                    path.reset()
                    path.moveTo(cx + s * 0.92f, cy - s * 0.85f)
                    path.lineTo(cx - s * 0.45f, cy)
                    path.lineTo(cx + s * 0.92f, cy + s * 0.85f)
                    path.close()
                    canvas.drawPath(path, iconPaint)
                }

                MediaGlyph.NEXT -> {
                    val barW = s * 0.30f
                    barRect.set(cx + s * 0.95f - barW, cy - s * 0.85f, cx + s * 0.95f, cy + s * 0.85f)
                    canvas.drawRoundRect(barRect, 1f * density, 1f * density, iconPaint)
                    path.reset()
                    path.moveTo(cx - s * 0.92f, cy - s * 0.85f)
                    path.lineTo(cx + s * 0.45f, cy)
                    path.lineTo(cx - s * 0.92f, cy + s * 0.85f)
                    path.close()
                    canvas.drawPath(path, iconPaint)
                }
            }
        }
    }

    private class PinnedProgressBarView(context: Context) : View(context) {
        var onSeekFraction: ((Float) -> Unit)? = null

        private val density = context.resources.displayMetrics.density
        private var progress: Float = 0f
        private var scrubbing: Boolean = false
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFFFFF }
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF0033.toInt() }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val rect = RectF()

        init {
            isClickable = true
            isFocusable = false
        }

        fun setColors(active: Int, track: Int) {
            activePaint.color = active
            trackPaint.color = track
            invalidate()
        }

        fun setProgress(fraction: Float) {
            if (scrubbing) return
            val clamped = fraction.coerceIn(0f, 1f)
            if (abs(progress - clamped) > 0.002f) {
                progress = clamped
                invalidate()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (width <= 0) return false
            val pad = 5f * density
            val usableW = (width - pad * 2f).coerceAtLeast(1f)
            val frac = ((event.x - pad) / usableW).coerceIn(0f, 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scrubbing = true
                    progress = frac
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    progress = frac
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    scrubbing = false
                    progress = frac
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSeekFraction?.invoke(frac)
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    scrubbing = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val pad = 5f * density
            val cy = h * 0.5f
            val trackH = (if (scrubbing) 4.2f else 3.0f) * density
            val halfH = trackH * 0.5f
            val left = pad
            val right = (w - pad).coerceAtLeast(left + 1f)
            val fillX = left + (right - left) * progress

            rect.set(left, cy - halfH, right, cy + halfH)
            canvas.drawRoundRect(rect, halfH, halfH, trackPaint)

            if (fillX > left) {
                rect.set(left, cy - halfH, fillX, cy + halfH)
                canvas.drawRoundRect(rect, halfH, halfH, activePaint)
            }

            val thumbR = (if (scrubbing) 5.5f else 3.8f) * density
            canvas.drawCircle(fillX, cy, thumbR, thumbPaint)
        }
    }
}
