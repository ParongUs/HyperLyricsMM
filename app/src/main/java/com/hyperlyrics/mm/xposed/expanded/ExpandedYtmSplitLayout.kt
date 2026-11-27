package com.hyperlyrics.mm.xposed.expanded

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import com.hyperlyrics.mm.common.ExpandedThemeStyle
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.common.ModuleSettings
import com.hyperlyrics.mm.lyrics.model.LyricsState
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Applies our YouTube Music Split-Lyrics Layout directly onto HyperOS's native
 * `PlayerIslandConstraintLayout` (real & dummy holders), keeping every original
 * button, seek bar, and album cover compact yet comfortably usable on the left
 * while flowing synchronized YouTube Music lyrics stream across the right half.
 *
 * Snapshots original native View properties & ConstraintSet so switching to any
 * non-YouTube Music app (Spotify, Apple Music, etc.) restores stock HyperOS cleanly.
 */
internal object ExpandedYtmSplitLayout {
    private const val TAG = "ExpandedYtmSplitLayout"
    private const val CONSTRAINT_SET_CLASS = "androidx.constraintlayout.widget.ConstraintSet"
    private const val CONSTRAINT_LP_CLASS = "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams"

    private const val SIDE_LEFT = 1
    private const val SIDE_RIGHT = 2
    private const val SIDE_TOP = 3
    private const val SIDE_BOTTOM = 4
    private const val SIDE_START = 6
    private const val SIDE_END = 7

    private const val TAG_BACKDROP = "hyperlyrics_mm_ytm_backdrop"
    private const val TAG_DIVIDER = "hyperlyrics_mm_ytm_divider"
    private const val TAG_LYRICS = "hyperlyrics_mm_ytm_lyrics"
    private const val TAG_PIN_TOGGLE = "hyperlyrics_mm_ytm_pin_toggle"

    private val playerStates = Collections.synchronizedMap(WeakHashMap<ViewGroup, PlayerState>())
    private val constraintApis = Collections.synchronizedMap(WeakHashMap<ClassLoader, ConstraintApi>())

    private val coverOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val radius = 10f * view.resources.displayMetrics.density
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    data class NativeHolderViews(
        val player: ViewGroup,
        val albumView: View,
        val albumImage: ImageView,
        val appIcon: ImageView?,
        val titleText: TextView,
        val artistText: TextView,
        val elapsedTime: TextView,
        val totalTime: TextView,
        val seekBar: View,
        val seamless: View,
        val actions: List<View>,
        val mediaBgView: View?
    )

    data class ResourceIds(
        val albumArt: Int,
        val albumArtImage: Int,
        val headerTitle: Int,
        val headerArtist: Int,
        val actions: Int,
        val actionIds: List<Int>,
        val mediaSeamless: Int,
        val mediaProgressBar: Int,
        val mediaElapsedTime: Int,
        val mediaTotalTime: Int
    ) {
        companion object {
            fun from(context: Context): ResourceIds? = runCatching {
                fun req(name: String): Int {
                    val id = context.resources.getIdentifier(name, "id", context.packageName)
                    require(id != 0) { "Missing id/$name" }
                    return id
                }
                fun opt(name: String): Int =
                    context.resources.getIdentifier(name, "id", context.packageName)

                ResourceIds(
                    albumArt = req("album_art"),
                    albumArtImage = opt("album_art_image"),
                    headerTitle = req("header_title"),
                    headerArtist = req("header_artist"),
                    actions = req("actions"),
                    actionIds = (0..4).map { req("action$it") },
                    mediaSeamless = req("media_seamless"),
                    mediaProgressBar = req("media_progress_bar"),
                    mediaElapsedTime = req("media_elapsed_time"),
                    mediaTotalTime = req("media_total_time")
                )
            }.onFailure {
                ModuleLogger.w(TAG, "Failed resolving SystemUI media island IDs", it)
            }.getOrNull()
        }
    }

    fun applyYouTubeMusicLayout(
        views: NativeHolderViews,
        settings: ModuleSettings,
        palette: YtmCardPalette,
        lyricsState: LyricsState,
        isPlaying: Boolean,
        positionProvider: () -> Long,
        onSeekRequested: (Long) -> Unit,
        onRetryRequested: () -> Unit,
        isPinned: Boolean,
        onPinToggleRequested: () -> Unit,
        applySeekBarColors: (View, Int, Int) -> Unit,
        resetSeekBarOffsets: (View) -> Unit
    ) {
        val player = views.player
        val classLoader = player.javaClass.classLoader ?: return
        val api = resolveConstraintApi(classLoader) ?: return
        val ids = ResourceIds.from(player.context) ?: return

        val state = synchronized(playerStates) {
            playerStates.getOrPut(player) { PlayerState.capture(views, api) }
        }

        val cardWidthPx = resolveCardWidthPx(player)
        val leftPanePx = (cardWidthPx * settings.leftPaneFraction).roundToInt()
            .coerceIn(dp(player.context, 134f), cardWidthPx - dp(player.context, 140f))

        // 1. Ensure backdrop, divider, FlowingLyricsView, and quick Pin toggle exist in player
        val backdrop = ensureBackdropView(player, api, state)
        val divider = ensureDividerView(player, api, state)
        val lyricsView = ensureLyricsView(player, api, state)
        val pinToggle = ensurePinToggleView(player, api, state)

        // 2. Apply YouTube Music theme backdrop vs HyperOS native glass
        val useYtmBackdrop = settings.expandedThemeStyle != ExpandedThemeStyle.HYPEROS_NATIVE
        if (useYtmBackdrop) {
            backdrop.visibility = View.VISIBLE
            backdrop.setPalette(palette, animate = state.ytmActive)
            views.mediaBgView?.alpha = 0f
        } else {
            backdrop.visibility = View.GONE
            views.mediaBgView?.alpha = state.origMediaBgAlpha
        }

        // Determine if 4th Like button actually has an icon & is visible (avoids off-center 3-button row!)
        val hasRealLikeAction = settings.showLikeButton &&
            views.actions.getOrNull(4)?.let { btn ->
                val origVis = state.origActionVisibilities.getOrNull(4) ?: View.GONE
                val hasDrawable = (btn as? ImageView)?.drawable != null
                origVis == View.VISIBLE && hasDrawable
            } == true

        // 3. Apply compact left-pane ConstraintSet cloned from native baseline
        val workingSet = api.cloneFrom(state.nativeConstraintSet)
        if (workingSet != null) {
            buildCompactLeftConstraints(
                api = api,
                set = workingSet,
                ids = ids,
                context = player.context,
                leftPanePx = leftPanePx,
                showLikeButton = hasRealLikeAction
            )
            api.applyTo(workingSet, player)
        }

        // 4. Sync nested album art dimensions & YouTube Music 10dp rounded cover
        val coverSize = dp(player.context, COVER_SIZE_DP)
        updateExactSize(views.albumView, coverSize, coverSize)
        updateExactSize(views.albumImage, coverSize, coverSize)
        views.albumView.outlineProvider = coverOutlineProvider
        views.albumView.clipToOutline = true
        views.albumImage.outlineProvider = coverOutlineProvider
        views.albumImage.clipToOutline = true
        views.appIcon?.visibility = View.GONE

        // 5. Compact crisp typography with smooth horizontal fade edges for marquee text
        val fadeLen = dp(player.context, 10f)
        views.titleText.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(fadeLen)
            isSelected = true
        }
        views.artistText.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.8f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(0xB8FFFFFF.toInt())
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(fadeLen)
            isSelected = true
        }
        views.elapsedTime.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            fontFeatureSettings = "tnum"
            setTextColor(0x99FFFFFF.toInt())
            visibility = View.VISIBLE
        }
        views.totalTime.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            fontFeatureSettings = "tnum"
            setTextColor(0x99FFFFFF.toInt())
            visibility = View.VISIBLE
        }

        // 6. Clean seekBar vertical padding + YouTube Music accent styling
        views.seekBar.setPadding(views.seekBar.paddingLeft, 0, views.seekBar.paddingRight, 0)
        resetSeekBarOffsets(views.seekBar)
        applySeekBarColors(views.seekBar, palette.progressActive, palette.progressTrack)

        styleActionButtons(views, hasRealLikeAction, palette, state)

        // 7. Position hairline divider & flowing lyrics theatre on the right half
        updateDividerLayout(divider, api, player.context, leftPanePx)
        divider.visibility = View.VISIBLE

        updateLyricsLayout(lyricsView, api, player.context, leftPanePx)
        lyricsView.visibility = View.VISIBLE
        lyricsView.positionProvider = positionProvider
        lyricsView.onSeekRequested = onSeekRequested
        lyricsView.onRetryRequested = onRetryRequested
        lyricsView.updateStyle(
            fontSizeSp = settings.expandedFontSizeSp,
            accentColor = palette.accent,
            syncOffsetMs = settings.syncOffsetMs,
            tapToSeek = settings.tapLyricToSeek,
            showInterludeDots = settings.showInterludeDots
        )
        lyricsView.updateLyricsState(lyricsState)
        lyricsView.updatePlaybackState(isPlaying)

        // 8. Optional 1-tap Pin toggle badge in top-right corner (always shown while pinned so user can unpin)
        val showPinToggle = settings.showPinButtonOnIsland || isPinned
        updatePinToggleLayout(pinToggle, api, player.context)
        pinToggle.visibility = if (showPinToggle) View.VISIBLE else View.GONE
        pinToggle.onPinToggleRequested = onPinToggleRequested
        pinToggle.updateState(isPinned = isPinned, accentColor = palette.accent)
        pinToggle.bringToFront()

        state.ytmActive = true
        state.lastLeftPanePx = leftPanePx
        state.lastHasRealLikeAction = hasRealLikeAction

        // Re-apply once real measured width arrives if player wasn't measured yet
        if (player.width == 0 && !state.layoutListenerAttached) {
            state.layoutListenerAttached = true
            player.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
                val newW = right - left
                val oldW = oldRight - oldLeft
                if (newW > 0 && newW != oldW && state.ytmActive) {
                    val refreshedLeft = (newW * settings.leftPaneFraction).roundToInt()
                        .coerceIn(dp(v.context, 134f), newW - dp(v.context, 140f))
                    if (kotlin.math.abs(refreshedLeft - state.lastLeftPanePx) > dp(v.context, 2f)) {
                        state.lastLeftPanePx = refreshedLeft
                        v.post {
                            if (state.ytmActive) {
                                updateDividerLayout(divider, api, v.context, refreshedLeft)
                                updateLyricsLayout(lyricsView, api, v.context, refreshedLeft)
                                api.cloneFrom(state.nativeConstraintSet)?.let { rebuilt ->
                                    buildCompactLeftConstraints(
                                        api, rebuilt, ids, v.context, refreshedLeft, state.lastHasRealLikeAction
                                    )
                                    api.applyTo(rebuilt, player)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun updateLiveLyricsOnly(
        player: ViewGroup,
        lyricsState: LyricsState,
        isPlaying: Boolean,
        settings: ModuleSettings,
        palette: YtmCardPalette
    ) {
        val state = synchronized(playerStates) { playerStates[player] } ?: return
        if (!state.ytmActive) return
        state.lyricsView?.apply {
            updateStyle(
                fontSizeSp = settings.expandedFontSizeSp,
                accentColor = palette.accent,
                syncOffsetMs = settings.syncOffsetMs,
                tapToSeek = settings.tapLyricToSeek,
                showInterludeDots = settings.showInterludeDots
            )
            updateLyricsState(lyricsState)
            updatePlaybackState(isPlaying)
        }
    }

    fun updatePinStateOnly(
        player: ViewGroup,
        isPinned: Boolean,
        settings: ModuleSettings,
        palette: YtmCardPalette
    ) {
        val state = synchronized(playerStates) { playerStates[player] } ?: return
        if (!state.ytmActive) return
        state.pinToggleView?.apply {
            visibility = if (settings.showPinButtonOnIsland || isPinned) View.VISIBLE else View.GONE
            updateState(isPinned = isPinned, accentColor = palette.accent)
        }
    }

    fun restoreNativeLayout(views: NativeHolderViews, restoreSeekBarOffsets: (View) -> Unit) {
        val player = views.player
        val state = synchronized(playerStates) { playerStates[player] } ?: return
        if (!state.ytmActive) return

        val api = player.javaClass.classLoader?.let(::resolveConstraintApi)
        state.backdropView?.visibility = View.GONE
        state.dividerView?.visibility = View.GONE
        state.lyricsView?.visibility = View.GONE
        state.pinToggleView?.visibility = View.GONE

        views.mediaBgView?.alpha = state.origMediaBgAlpha
        views.appIcon?.visibility = state.origAppIconVisibility
        views.albumView.outlineProvider = state.origAlbumOutline
        views.albumView.clipToOutline = state.origAlbumClip
        views.albumImage.outlineProvider = state.origImageOutline
        views.albumImage.clipToOutline = state.origImageClip

        views.titleText.setTextSize(TypedValue.COMPLEX_UNIT_PX, state.origTitleSizePx)
        views.titleText.typeface = state.origTitleTypeface
        views.artistText.setTextSize(TypedValue.COMPLEX_UNIT_PX, state.origArtistSizePx)
        views.artistText.typeface = state.origArtistTypeface
        views.elapsedTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, state.origElapsedSizePx)
        views.totalTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, state.origTotalSizePx)

        views.actions.getOrNull(2)?.background = state.origPlayPauseBackground
        views.actions.forEachIndexed { idx, btn ->
            btn.scaleX = 1f
            btn.scaleY = 1f
            state.origActionVisibilities.getOrNull(idx)?.let { btn.visibility = it }
        }
        views.seamless.scaleX = 1f
        views.seamless.scaleY = 1f
        views.seamless.visibility = state.origSeamlessVisibility
        state.origSeamlessTextVisibilities.forEach { (tv, vis) -> tv.visibility = vis }
        state.origSeamlessTextVisibilities.clear()

        views.seekBar.setPadding(
            views.seekBar.paddingLeft,
            state.origSeekBarPadTop,
            views.seekBar.paddingRight,
            state.origSeekBarPadBottom
        )
        restoreSeekBarOffsets(views.seekBar)
        if (api != null) {
            api.applyTo(state.nativeConstraintSet, player)
        }
        state.ytmActive = false
    }

    private fun buildCompactLeftConstraints(
        api: ConstraintApi,
        set: Any,
        ids: ResourceIds,
        context: Context,
        leftPanePx: Int,
        showLikeButton: Boolean
    ) {
        val startPad = dp(context, 12f)
        val rightInset = dp(context, 9f)
        val coverSize = dp(context, COVER_SIZE_DP)
        val coverTop = dp(context, 13f)
        val infoGap = dp(context, 8f)
        val seamlessSize = dp(context, 22f)

        // 1. Album Art (44dp x 44dp rounded square, top-left)
        api.clearAll(set, ids.albumArt)
        api.constrainWidth(set, ids.albumArt, coverSize)
        api.constrainHeight(set, ids.albumArt, coverSize)
        api.connect(set, ids.albumArt, SIDE_START, 0, SIDE_START)
        api.connect(set, ids.albumArt, SIDE_TOP, 0, SIDE_TOP)
        api.setMargin(set, ids.albumArt, SIDE_START, startPad)
        api.setMargin(set, ids.albumArt, SIDE_TOP, coverTop)
        api.setVisibility(set, ids.albumArt, View.VISIBLE)

        // 2. Output Switcher (media_seamless): neat 22dp icon badge at top-right of left pane
        api.clearAll(set, ids.mediaSeamless)
        api.constrainWidth(set, ids.mediaSeamless, seamlessSize)
        api.constrainHeight(set, ids.mediaSeamless, seamlessSize)
        api.connect(set, ids.mediaSeamless, SIDE_START, 0, SIDE_START)
        api.connect(set, ids.mediaSeamless, SIDE_TOP, ids.albumArt, SIDE_TOP)
        api.setMargin(
            set,
            ids.mediaSeamless,
            SIDE_START,
            (leftPanePx - rightInset - seamlessSize).coerceAtLeast(startPad + coverSize + infoGap)
        )
        api.setMargin(set, ids.mediaSeamless, SIDE_TOP, dp(context, 1f))

        // 3. Title & Artist (strictly bounded inside left pane with clean gap before output badge)
        val textStart = startPad + coverSize + infoGap
        val titleWidth = (leftPanePx - rightInset - seamlessSize - dp(context, 7f) - textStart)
            .coerceAtLeast(dp(context, 48f))
        val artistWidth = (leftPanePx - rightInset - textStart)
            .coerceAtLeast(dp(context, 56f))

        api.clearAll(set, ids.headerTitle)
        api.constrainWidth(set, ids.headerTitle, titleWidth)
        api.constrainHeight(set, ids.headerTitle, ViewGroup.LayoutParams.WRAP_CONTENT)
        api.connect(set, ids.headerTitle, SIDE_START, ids.albumArt, SIDE_END)
        api.connect(set, ids.headerTitle, SIDE_TOP, ids.albumArt, SIDE_TOP)
        api.setMargin(set, ids.headerTitle, SIDE_START, infoGap)
        api.setMargin(set, ids.headerTitle, SIDE_TOP, dp(context, 3f))

        api.clearAll(set, ids.headerArtist)
        api.constrainWidth(set, ids.headerArtist, artistWidth)
        api.constrainHeight(set, ids.headerArtist, ViewGroup.LayoutParams.WRAP_CONTENT)
        api.connect(set, ids.headerArtist, SIDE_START, ids.headerTitle, SIDE_START)
        api.connect(set, ids.headerArtist, SIDE_TOP, ids.headerTitle, SIDE_BOTTOM)
        api.setMargin(set, ids.headerArtist, SIDE_TOP, dp(context, 2f))

        // 4. Progress Bar (tight height so timestamps sit right below with zero awkward gap)
        val seekWidth = (leftPanePx - startPad - rightInset).coerceAtLeast(dp(context, 100f))
        api.clearAll(set, ids.mediaProgressBar)
        api.constrainWidth(set, ids.mediaProgressBar, seekWidth)
        api.constrainHeight(set, ids.mediaProgressBar, dp(context, 18f))
        api.connect(set, ids.mediaProgressBar, SIDE_START, 0, SIDE_START)
        api.connect(set, ids.mediaProgressBar, SIDE_TOP, ids.albumArt, SIDE_BOTTOM)
        api.setMargin(set, ids.mediaProgressBar, SIDE_START, startPad)
        api.setMargin(set, ids.mediaProgressBar, SIDE_TOP, dp(context, 12f))

        // 5. Elapsed & Total Time neatly tucked right under progress bar ends
        api.clearAll(set, ids.mediaElapsedTime)
        api.constrainWidth(set, ids.mediaElapsedTime, ViewGroup.LayoutParams.WRAP_CONTENT)
        api.connect(set, ids.mediaElapsedTime, SIDE_START, ids.mediaProgressBar, SIDE_START)
        api.connect(set, ids.mediaElapsedTime, SIDE_TOP, ids.mediaProgressBar, SIDE_BOTTOM)
        api.setMargin(set, ids.mediaElapsedTime, SIDE_TOP, dp(context, 1f))

        api.clearAll(set, ids.mediaTotalTime)
        api.constrainWidth(set, ids.mediaTotalTime, ViewGroup.LayoutParams.WRAP_CONTENT)
        api.connect(set, ids.mediaTotalTime, SIDE_END, ids.mediaProgressBar, SIDE_END)
        api.connect(set, ids.mediaTotalTime, SIDE_TOP, ids.mediaProgressBar, SIDE_BOTTOM)
        api.setMargin(set, ids.mediaTotalTime, SIDE_TOP, dp(context, 1f))

        // 6. Compact, still-comfortable Action Buttons row at bottom of left pane
        // action0 (dislike) -> GONE to keep layout clean; action1=Prev, action2=Play/Pause, action3=Next, action4=Like
        val action0 = ids.actionIds[0]
        val action1 = ids.actionIds[1]
        val action2 = ids.actionIds[2]
        val action3 = ids.actionIds[3]
        val action4 = ids.actionIds[4]

        api.clearAll(set, action0)
        api.setVisibility(set, action0, View.GONE)

        val visibleActions = if (showLikeButton) {
            listOf(action1, action2, action3, action4)
        } else {
            api.clearAll(set, action4)
            api.setVisibility(set, action4, View.GONE)
            listOf(action1, action2, action3)
        }

        val btnSizes = if (showLikeButton) {
            intArrayOf(dp(context, 33f), dp(context, 39f), dp(context, 33f), dp(context, 30f))
        } else {
            intArrayOf(dp(context, 36f), dp(context, 42f), dp(context, 36f))
        }

        val totalBtnWidth = btnSizes.sum()
        val usableRowW = (leftPanePx - startPad - rightInset).coerceAtLeast(totalBtnWidth)
        val gap = if (visibleActions.size > 1) {
            ((usableRowW - totalBtnWidth) / (visibleActions.size - 1)).coerceAtLeast(dp(context, 2f))
        } else {
            0
        }
        val rowStartX = startPad + ((usableRowW - totalBtnWidth - gap * (visibleActions.size - 1)) / 2)
            .coerceAtLeast(0)
        val bottomMargin = dp(context, 12f)
        val playSize = btnSizes[1]

        var cursorX = rowStartX
        visibleActions.forEachIndexed { idx, actionId ->
            val size = btnSizes[idx]
            val extraBottom = (playSize - size) / 2
            api.clearAll(set, actionId)
            api.setVisibility(set, actionId, View.VISIBLE)
            api.constrainWidth(set, actionId, size)
            api.constrainHeight(set, actionId, size)
            api.connect(set, actionId, SIDE_START, 0, SIDE_START)
            api.connect(set, actionId, SIDE_BOTTOM, 0, SIDE_BOTTOM)
            api.setMargin(set, actionId, SIDE_START, cursorX)
            api.setMargin(set, actionId, SIDE_BOTTOM, bottomMargin + extraBottom)
            cursorX += size + gap
        }
    }

    private fun styleActionButtons(
        views: NativeHolderViews,
        showLikeButton: Boolean,
        palette: YtmCardPalette,
        state: PlayerState
    ) {
        val ctx = views.player.context
        views.actions.getOrNull(0)?.visibility = View.GONE
        views.actions.getOrNull(4)?.visibility = if (showLikeButton) View.VISIBLE else View.GONE

        // YouTube Music signature crisp circular frosted highlight behind Play/Pause (action2)
        views.actions.getOrNull(2)?.let { playPauseBtn ->
            val pill = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x28FFFFFF)
                setStroke(dp(ctx, 0.85f).coerceAtLeast(1), Color.argb(68, Color.red(palette.accent), Color.green(palette.accent), Color.blue(palette.accent)))
            }
            playPauseBtn.background = pill
            val pad = dp(ctx, 7f)
            playPauseBtn.setPadding(pad, pad, pad, pad)
        }

        // Crisp subtle padding on Prev / Next / Like
        listOf(1, 3, 4).forEach { idx ->
            views.actions.getOrNull(idx)?.let { btn ->
                val pad = dp(ctx, 5f)
                btn.setPadding(pad, pad, pad, pad)
                (btn as? ImageView)?.imageTintList = ColorStateList.valueOf(0xEBFFFFFF.toInt())
            }
        }
        (views.actions.getOrNull(2) as? ImageView)?.imageTintList = ColorStateList.valueOf(Color.WHITE)

        // Keep media_seamless strictly icon-only (hide any clipped device-name TextView causing `/` slivers)
        views.seamless.scaleX = 0.82f
        views.seamless.scaleY = 0.82f
        (views.seamless as? ViewGroup)?.let { vg ->
            vg.clipChildren = true
            vg.clipToPadding = true
            hideSeamlessTextLabels(vg, state)
        }
    }

    private fun hideSeamlessTextLabels(group: ViewGroup, state: PlayerState) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            when (child) {
                is TextView -> {
                    if (!state.origSeamlessTextVisibilities.containsKey(child)) {
                        state.origSeamlessTextVisibilities[child] = child.visibility
                    }
                    child.visibility = View.GONE
                }
                is ViewGroup -> hideSeamlessTextLabels(child, state)
            }
        }
    }

    private fun ensureBackdropView(
        player: ViewGroup,
        api: ConstraintApi,
        state: PlayerState
    ): YtmAmbientBackdropView {
        state.backdropView?.takeIf { it.parent === player }?.let { return it }
        (player.findViewWithTag<View>(TAG_BACKDROP) as? YtmAmbientBackdropView)?.let {
            state.backdropView = it
            return it
        }
        val view = YtmAmbientBackdropView(player.context).apply {
            id = View.generateViewId()
            tag = TAG_BACKDROP
        }
        val lp = api.createMatchParentParams()
        // Insert at index 0 behind all native controls
        player.addView(view, 0, lp)
        state.backdropView = view
        return view
    }

    private fun ensureDividerView(
        player: ViewGroup,
        api: ConstraintApi,
        state: PlayerState
    ): View {
        state.dividerView?.takeIf { it.parent === player }?.let { return it }
        player.findViewWithTag<View>(TAG_DIVIDER)?.let {
            state.dividerView = it
            return it
        }
        val divider = View(player.context).apply {
            id = View.generateViewId()
            tag = TAG_DIVIDER
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0x26FFFFFF, 0x26FFFFFF, Color.TRANSPARENT)
            )
        }
        player.addView(divider, api.createLayoutParams(1, 0))
        state.dividerView = divider
        return divider
    }

    private fun ensureLyricsView(
        player: ViewGroup,
        api: ConstraintApi,
        state: PlayerState
    ): FlowingLyricsView {
        state.lyricsView?.takeIf { it.parent === player }?.let { return it }
        (player.findViewWithTag<View>(TAG_LYRICS) as? FlowingLyricsView)?.let {
            state.lyricsView = it
            return it
        }
        val view = FlowingLyricsView(player.context).apply {
            id = View.generateViewId()
            tag = TAG_LYRICS
        }
        player.addView(view, api.createLayoutParams(0, 0))
        state.lyricsView = view
        return view
    }

    private fun ensurePinToggleView(
        player: ViewGroup,
        api: ConstraintApi,
        state: PlayerState
    ): IslandPinToggleView {
        state.pinToggleView?.takeIf { it.parent === player }?.let { return it }
        (player.findViewWithTag<View>(TAG_PIN_TOGGLE) as? IslandPinToggleView)?.let {
            state.pinToggleView = it
            return it
        }
        val sizePx = dp(player.context, 24f)
        val view = IslandPinToggleView(player.context).apply {
            id = View.generateViewId()
            tag = TAG_PIN_TOGGLE
            elevation = dp(player.context, 4f).toFloat()
        }
        player.addView(view, api.createLayoutParams(sizePx, sizePx))
        state.pinToggleView = view
        return view
    }

    private fun updatePinToggleLayout(pinToggle: View, api: ConstraintApi, context: Context) {
        val sizePx = dp(context, 24f)
        val lp = api.createLayoutParams(sizePx, sizePx)
        api.setConstraints(lp, endToEnd = 0, topToTop = 0)
        lp.marginEnd = dp(context, 9f)
        lp.topMargin = dp(context, 8f)
        pinToggle.layoutParams = lp
    }

    private fun updateDividerLayout(divider: View, api: ConstraintApi, context: Context, leftPanePx: Int) {
        val lp = api.createLayoutParams(dp(context, 1f).coerceAtLeast(1), 0)
        api.setConstraints(lp, startToStart = 0, topToTop = 0, bottomToBottom = 0)
        lp.marginStart = leftPanePx
        lp.topMargin = dp(context, 14f)
        lp.bottomMargin = dp(context, 14f)
        divider.layoutParams = lp
    }

    private fun updateLyricsLayout(lyricsView: View, api: ConstraintApi, context: Context, leftPanePx: Int) {
        val lp = api.createLayoutParams(0, 0)
        api.setConstraints(lp, startToStart = 0, endToEnd = 0, topToTop = 0, bottomToBottom = 0)
        lp.marginStart = leftPanePx + dp(context, 9f)
        lp.marginEnd = dp(context, 12f)
        lp.topMargin = dp(context, 6f)
        lp.bottomMargin = dp(context, 6f)
        lyricsView.layoutParams = lp
    }

    private fun resolveCardWidthPx(player: ViewGroup): Int {
        if (player.width > 0) return player.width
        if (player.measuredWidth > 0) return player.measuredWidth
        val screenW = player.resources.displayMetrics.widthPixels
        return (screenW - dp(player.context, 28f)).coerceAtLeast(dp(player.context, 310f))
    }

    private fun updateExactSize(view: View, w: Int, h: Int) {
        val lp = view.layoutParams ?: return
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            view.layoutParams = lp
        }
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun resolveConstraintApi(classLoader: ClassLoader): ConstraintApi? {
        return synchronized(constraintApis) {
            constraintApis.getOrPut(classLoader) {
                ConstraintApi.create(classLoader) ?: return null
            }
        }
    }

    private class PlayerState(
        val nativeConstraintSet: Any,
        val origMediaBgAlpha: Float,
        val origAppIconVisibility: Int,
        val origAlbumOutline: ViewOutlineProvider?,
        val origAlbumClip: Boolean,
        val origImageOutline: ViewOutlineProvider?,
        val origImageClip: Boolean,
        val origTitleSizePx: Float,
        val origTitleTypeface: Typeface?,
        val origArtistSizePx: Float,
        val origArtistTypeface: Typeface?,
        val origElapsedSizePx: Float,
        val origTotalSizePx: Float,
        val origPlayPauseBackground: Drawable?,
        val origActionVisibilities: List<Int>,
        val origSeamlessVisibility: Int,
        val origSeekBarPadTop: Int,
        val origSeekBarPadBottom: Int
    ) {
        var ytmActive: Boolean = false
        var lastLeftPanePx: Int = 0
        var lastHasRealLikeAction: Boolean = false
        var layoutListenerAttached: Boolean = false
        var backdropView: YtmAmbientBackdropView? = null
        var dividerView: View? = null
        var lyricsView: FlowingLyricsView? = null
        var pinToggleView: IslandPinToggleView? = null
        val origSeamlessTextVisibilities = WeakHashMap<TextView, Int>()

        companion object {
            fun capture(views: NativeHolderViews, api: ConstraintApi): PlayerState {
                val nativeSet = api.cloneFromLayout(views.player)
                    ?: error("Unable to capture ConstraintSet from PlayerIslandConstraintLayout")
                return PlayerState(
                    nativeConstraintSet = nativeSet,
                    origMediaBgAlpha = views.mediaBgView?.alpha ?: 1f,
                    origAppIconVisibility = views.appIcon?.visibility ?: View.VISIBLE,
                    origAlbumOutline = views.albumView.outlineProvider,
                    origAlbumClip = views.albumView.clipToOutline,
                    origImageOutline = views.albumImage.outlineProvider,
                    origImageClip = views.albumImage.clipToOutline,
                    origTitleSizePx = views.titleText.textSize,
                    origTitleTypeface = views.titleText.typeface,
                    origArtistSizePx = views.artistText.textSize,
                    origArtistTypeface = views.artistText.typeface,
                    origElapsedSizePx = views.elapsedTime.textSize,
                    origTotalSizePx = views.totalTime.textSize,
                    origPlayPauseBackground = views.actions.getOrNull(2)?.background,
                    origActionVisibilities = views.actions.map { it.visibility },
                    origSeamlessVisibility = views.seamless.visibility,
                    origSeekBarPadTop = views.seekBar.paddingTop,
                    origSeekBarPadBottom = views.seekBar.paddingBottom
                )
            }
        }
    }

    private class ConstraintApi private constructor(
        private val constraintSetCtor: Constructor<*>,
        private val cloneFromLayoutMethod: Method,
        private val cloneFromSetMethod: Method,
        private val applyToMethod: Method,
        private val connectMethod: Method,
        private val setMarginMethod: Method,
        private val clearMethod: Method,
        private val setVisibilityMethod: Method?,
        private val constrainWidthMethod: Method?,
        private val constrainHeightMethod: Method?,
        private val lpCtor: Constructor<*>,
        private val startToStartField: Field,
        private val endToEndField: Field,
        private val topToTopField: Field,
        private val bottomToBottomField: Field
    ) {
        fun cloneFromLayout(layout: ViewGroup): Any? = runCatching {
            val cs = constraintSetCtor.newInstance()
            cloneFromLayoutMethod.invoke(cs, layout)
            cs
        }.getOrNull()

        fun cloneFrom(source: Any): Any? = runCatching {
            val cs = constraintSetCtor.newInstance()
            cloneFromSetMethod.invoke(cs, source)
            cs
        }.getOrNull()

        fun applyTo(set: Any, layout: ViewGroup) {
            runCatching { applyToMethod.invoke(set, layout) }
        }

        fun connect(set: Any, startId: Int, startSide: Int, endId: Int, endSide: Int) {
            runCatching { connectMethod.invoke(set, startId, startSide, endId, endSide) }
        }

        fun setMargin(set: Any, viewId: Int, side: Int, margin: Int) {
            runCatching { setMarginMethod.invoke(set, viewId, side, margin) }
        }

        fun clear(set: Any, viewId: Int, side: Int) {
            runCatching { clearMethod.invoke(set, viewId, side) }
        }

        fun clearAll(set: Any, viewId: Int) {
            clear(set, viewId, SIDE_LEFT)
            clear(set, viewId, SIDE_RIGHT)
            clear(set, viewId, SIDE_START)
            clear(set, viewId, SIDE_END)
            clear(set, viewId, SIDE_TOP)
            clear(set, viewId, SIDE_BOTTOM)
        }

        fun setVisibility(set: Any, viewId: Int, visibility: Int) {
            runCatching { setVisibilityMethod?.invoke(set, viewId, visibility) }
        }

        fun constrainWidth(set: Any, viewId: Int, width: Int) {
            runCatching { constrainWidthMethod?.invoke(set, viewId, width) }
        }

        fun constrainHeight(set: Any, viewId: Int, height: Int) {
            runCatching { constrainHeightMethod?.invoke(set, viewId, height) }
        }

        fun createLayoutParams(width: Int, height: Int): ViewGroup.MarginLayoutParams {
            return lpCtor.newInstance(width, height) as ViewGroup.MarginLayoutParams
        }

        fun createMatchParentParams(): ViewGroup.MarginLayoutParams {
            val lp = createLayoutParams(0, 0)
            setConstraints(lp, startToStart = 0, endToEnd = 0, topToTop = 0, bottomToBottom = 0)
            return lp
        }

        fun setConstraints(
            lp: ViewGroup.MarginLayoutParams,
            startToStart: Int = -1,
            endToEnd: Int = -1,
            topToTop: Int = -1,
            bottomToBottom: Int = -1
        ) {
            if (startToStart >= 0) startToStartField.setInt(lp, startToStart)
            if (endToEnd >= 0) endToEndField.setInt(lp, endToEnd)
            if (topToTop >= 0) topToTopField.setInt(lp, topToTop)
            if (bottomToBottom >= 0) bottomToBottomField.setInt(lp, bottomToBottom)
        }

        companion object {
            fun create(classLoader: ClassLoader): ConstraintApi? = runCatching {
                val csClass = classLoader.loadClass(CONSTRAINT_SET_CLASS)
                val clClass = classLoader.loadClass("androidx.constraintlayout.widget.ConstraintLayout")
                val lpClass = classLoader.loadClass(CONSTRAINT_LP_CLASS)
                val intType = Int::class.javaPrimitiveType!!

                ConstraintApi(
                    constraintSetCtor = csClass.getDeclaredConstructor().apply { isAccessible = true },
                    cloneFromLayoutMethod = csClass.getDeclaredMethod("clone", clClass).apply { isAccessible = true },
                    cloneFromSetMethod = csClass.getDeclaredMethod("clone", csClass).apply { isAccessible = true },
                    applyToMethod = csClass.getDeclaredMethod("applyTo", clClass).apply { isAccessible = true },
                    connectMethod = csClass.getDeclaredMethod("connect", intType, intType, intType, intType)
                        .apply { isAccessible = true },
                    setMarginMethod = csClass.getDeclaredMethod("setMargin", intType, intType, intType)
                        .apply { isAccessible = true },
                    clearMethod = csClass.getDeclaredMethod("clear", intType, intType)
                        .apply { isAccessible = true },
                    setVisibilityMethod = csClass.declaredMethods.firstOrNull {
                        it.name == "setVisibility" && it.parameterTypes.contentEquals(arrayOf(intType, intType))
                    }?.apply { isAccessible = true },
                    constrainWidthMethod = csClass.declaredMethods.firstOrNull {
                        it.name == "constrainWidth" && it.parameterTypes.contentEquals(arrayOf(intType, intType))
                    }?.apply { isAccessible = true },
                    constrainHeightMethod = csClass.declaredMethods.firstOrNull {
                        it.name == "constrainHeight" && it.parameterTypes.contentEquals(arrayOf(intType, intType))
                    }?.apply { isAccessible = true },
                    lpCtor = lpClass.getDeclaredConstructor(intType, intType).apply { isAccessible = true },
                    startToStartField = lpClass.getField("startToStart").apply { isAccessible = true },
                    endToEndField = lpClass.getField("endToEnd").apply { isAccessible = true },
                    topToTopField = lpClass.getField("topToTop").apply { isAccessible = true },
                    bottomToBottomField = lpClass.getField("bottomToBottom").apply { isAccessible = true }
                )
            }.onFailure {
                ModuleLogger.w(TAG, "ConstraintLayout reflection unavailable", it)
            }.getOrNull()
        }
    }

    private const val COVER_SIZE_DP = 44f
}
