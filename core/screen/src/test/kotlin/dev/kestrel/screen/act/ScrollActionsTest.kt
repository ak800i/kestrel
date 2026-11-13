package dev.kestrel.screen.act

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollActionsTest {
    private val forward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    private val backward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    private val up = android.R.id.accessibilityActionScrollUp
    private val down = android.R.id.accessibilityActionScrollDown
    private val right = android.R.id.accessibilityActionScrollRight
    private val pageUp = android.R.id.accessibilityActionPageUp
    private val pageDown = android.R.id.accessibilityActionPageDown
    private val pageRight = android.R.id.accessibilityActionPageRight

    @Test
    fun theDirectionalActionsTellTheAxis() {
        assertEquals(ScrollAxis.VERTICAL, ScrollActions.axis(setOf(forward, down), "android.widget.ScrollView", null, null))
        assertEquals(ScrollAxis.HORIZONTAL, ScrollActions.axis(setOf(forward, right), "android.widget.HorizontalScrollView", null, null))
        assertEquals(ScrollAxis.BOTH, ScrollActions.axis(setOf(down, right), "android.webkit.WebView", null, null))
    }

    @Test
    fun withoutDirectionalActionsTheClassOrTheShapeOfTheListTellsTheAxis() {
        assertEquals(ScrollAxis.HORIZONTAL, ScrollActions.axis(setOf(forward), "androidx.viewpager.widget.ViewPager", null, null))
        assertEquals(ScrollAxis.HORIZONTAL, ScrollActions.axis(setOf(forward), "android.support.v4.view.ViewPager", null, null))
        assertEquals(ScrollAxis.HORIZONTAL, ScrollActions.axis(setOf(forward), "androidx.recyclerview.widget.RecyclerView", 1, 12))
        assertEquals(ScrollAxis.VERTICAL, ScrollActions.axis(setOf(forward), "androidx.recyclerview.widget.RecyclerView", 12, 1))
        assertEquals(ScrollAxis.UNKNOWN, ScrollActions.axis(setOf(forward, backward), "android.view.View", null, null))
    }

    @Test
    fun forwardAndBackwardAreUsedOnlyAlongTheListsAxis() {
        assertEquals(listOf(down, pageDown, forward), ScrollActions.forDirection(ScrollDirection.DOWN, ScrollAxis.VERTICAL))
        assertEquals(listOf(down, pageDown), ScrollActions.forDirection(ScrollDirection.DOWN, ScrollAxis.HORIZONTAL))
        assertEquals(listOf(right, pageRight, forward), ScrollActions.forDirection(ScrollDirection.RIGHT, ScrollAxis.HORIZONTAL))
        assertEquals(listOf(right, pageRight), ScrollActions.forDirection(ScrollDirection.RIGHT, ScrollAxis.UNKNOWN))
        assertEquals(listOf(up, pageUp, backward), ScrollActions.forDirection(ScrollDirection.UP, ScrollAxis.UNKNOWN))
        assertEquals(listOf(down, pageDown), ScrollActions.forDirection(ScrollDirection.DOWN, ScrollAxis.BOTH))
    }

    @Test
    fun aSidewaysSwipeCrossesOnlyAListThatMovesSideways() {
        assertTrue(ScrollActions.movesAlong(ScrollAxis.HORIZONTAL, ScrollDirection.RIGHT))
        assertFalse(ScrollActions.movesAlong(ScrollAxis.VERTICAL, ScrollDirection.RIGHT))
        assertFalse(ScrollActions.movesAlong(ScrollAxis.UNKNOWN, ScrollDirection.LEFT))
        assertTrue(ScrollActions.movesAlong(ScrollAxis.UNKNOWN, ScrollDirection.DOWN))
        assertFalse(ScrollActions.movesAlong(ScrollAxis.HORIZONTAL, ScrollDirection.DOWN))
        assertTrue(ScrollActions.movesAlong(ScrollAxis.BOTH, ScrollDirection.LEFT))
    }
}
