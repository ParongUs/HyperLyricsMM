package com.hyperlyrics.mm.xposed.expanded

import android.view.MotionEvent
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandedPinPolicyTest {

    @Test
    fun `manual override controls pin state while master and expanded are enabled`() {
        assertFalse(
            ExpandedPinPolicy.isEffectivelyPinned(
                masterEnabled = true,
                expandedLyricsEnabled = true,
                autoPinSetting = false,
                manualPinOverride = null
            )
        )
        assertTrue(
            ExpandedPinPolicy.isEffectivelyPinned(
                masterEnabled = true,
                expandedLyricsEnabled = true,
                autoPinSetting = false,
                manualPinOverride = true
            )
        )
        assertFalse(
            ExpandedPinPolicy.isEffectivelyPinned(
                masterEnabled = true,
                expandedLyricsEnabled = true,
                autoPinSetting = true,
                manualPinOverride = false
            )
        )
        assertFalse(
            ExpandedPinPolicy.isEffectivelyPinned(
                masterEnabled = false,
                expandedLyricsEnabled = true,
                autoPinSetting = true,
                manualPinOverride = true
            )
        )
    }

    @Test
    fun `computePinnedWindowFlags enables pass-through and clears dim and outside watch`() {
        val original = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND

        val pinned = ExpandedPinPolicy.computePinnedWindowFlags(original)

        assertTrue(pinned and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertTrue(pinned and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(0, pinned and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        assertEquals(0, pinned and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Unrelated flags preserved
        assertTrue(pinned and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
        assertTrue(pinned and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED != 0)
    }

    @Test
    fun `shouldBlockOutsideTouch allows touches inside card and blocks outside touches`() {
        assertTrue(
            ExpandedPinPolicy.shouldBlockOutsideTouch(
                actionMasked = MotionEvent.ACTION_OUTSIDE,
                x = 120f,
                y = 120f,
                cardLeft = 40,
                cardTop = 80,
                cardRight = 1040,
                cardBottom = 520
            )
        )
        // Inside card -> allowed
        assertFalse(
            ExpandedPinPolicy.shouldBlockOutsideTouch(
                actionMasked = MotionEvent.ACTION_DOWN,
                x = 500f,
                y = 250f,
                cardLeft = 40,
                cardTop = 80,
                cardRight = 1040,
                cardBottom = 520
            )
        )
        // Below card on underlying app -> blocked from dismissing island
        assertTrue(
            ExpandedPinPolicy.shouldBlockOutsideTouch(
                actionMasked = MotionEvent.ACTION_DOWN,
                x = 500f,
                y = 900f,
                cardLeft = 40,
                cardTop = 80,
                cardRight = 1040,
                cardBottom = 520
            )
        )
    }

    @Test
    fun `isAutoCollapseMethodName matches outside and timeout collapse without blocking detach or swipe`() {
        assertTrue(ExpandedPinPolicy.isAutoCollapseMethodName("onTouchOutside"))
        assertTrue(ExpandedPinPolicy.isAutoCollapseMethodName("handleOutsideTouch"))
        assertTrue(ExpandedPinPolicy.isAutoCollapseMethodName("scheduleAutoCollapse"))
        assertTrue(ExpandedPinPolicy.isAutoCollapseMethodName("collapseByTimeout"))

        assertFalse(ExpandedPinPolicy.isAutoCollapseMethodName("detach"))
        assertFalse(ExpandedPinPolicy.isAutoCollapseMethodName("onDestroy"))
        assertFalse(ExpandedPinPolicy.isAutoCollapseMethodName("onMediaDataRemoved"))
        assertFalse(ExpandedPinPolicy.isAutoCollapseMethodName("onUserSwipeCollapse"))
    }
}
