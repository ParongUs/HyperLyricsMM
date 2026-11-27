package com.hyperlyrics.mm.xposed.expanded

import android.view.MotionEvent
import android.view.WindowManager
import java.util.Locale

/**
 * Pure policy logic for Expanded Hyper Island non-blocking Pin/Float mode.
 * Kept framework-light so every rule is covered by JVM unit tests.
 */
object ExpandedPinPolicy {

    /**
     * Resolves whether the expanded island should currently be pinned open.
     * A manual tap on the island's Pin toggle overrides the default auto-pin setting
     * for the lifetime of the current expanded session.
     */
    fun isEffectivelyPinned(
        masterEnabled: Boolean,
        expandedLyricsEnabled: Boolean,
        autoPinSetting: Boolean,
        manualPinOverride: Boolean?
    ): Boolean {
        if (!masterEnabled || !expandedLyricsEnabled) return false
        return manualPinOverride ?: autoPinSetting
    }

    /**
     * Computes WindowManager.LayoutParams flags so the expanded island stays interactive
     * within its own card bounds while letting all outside touches, gestures, and keyboard
     * focus pass seamlessly through to underlying apps without dimming the screen.
     */
    fun computePinnedWindowFlags(originalFlags: Int): Int {
        val addFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val clearFlags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        return (originalFlags or addFlags) and clearFlags.inv()
    }

    /**
     * Determines whether a touch event dispatched to the expanded island's root window
     * should be blocked from triggering HyperOS's outside-touch dismissal while pinned.
     */
    fun shouldBlockOutsideTouch(
        actionMasked: Int,
        x: Float,
        y: Float,
        cardLeft: Int,
        cardTop: Int,
        cardRight: Int,
        cardBottom: Int
    ): Boolean {
        if (actionMasked == MotionEvent.ACTION_OUTSIDE) return true
        val hasValidBounds = cardRight > cardLeft && cardBottom > cardTop
        if (!hasValidBounds) return false
        return x < cardLeft || x > cardRight || y < cardTop || y > cardBottom
    }

    /**
     * Narrowly matches HyperOS Dynamic Island outside-touch and timeout auto-collapse
     * methods while never matching explicit detach/destroy/user-swipe lifecycle methods.
     */
    fun isAutoCollapseMethodName(methodName: String): Boolean {
        val lower = methodName.lowercase(Locale.US)
        if (lower.contains("detach") ||
            lower.contains("destroy") ||
            lower.contains("remove") ||
            lower.contains("swipe") ||
            lower.contains("user")
        ) {
            return false
        }
        return lower in EXACT_AUTO_COLLAPSE_NAMES ||
            (lower.contains("outside") && (lower.contains("touch") || lower.contains("dismiss") || lower.contains("collapse"))) ||
            (lower.contains("timeout") && (lower.contains("collapse") || lower.contains("dismiss") || lower.contains("hide"))) ||
            (lower.contains("autocollapse") || lower.contains("autodismiss"))
    }

    private val EXACT_AUTO_COLLAPSE_NAMES = setOf(
        "ontouchoutside",
        "handletouchoutside",
        "handleoutsidetouch",
        "onoutsidetouch",
        "dismissonoutsidetouch",
        "schedulecollapse",
        "scheduleautocollapse",
        "collapsebytimeout",
        "collapseafterdelay",
        "ontimeoutcollapse",
        "dismissbytimeout",
        "hidebytimeout"
    )
}
