package dev.kestrel.screen.act

import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GesturePathsTest {
    /** kestrel-api37's screen: a Pixel 8, 1080 by 2400 pixels. */
    private val screen = IntRect(0, 0, 1080, 2400)

    @Test
    fun aPinchAtTheCentreCoversAtMostEightyPercentOfTheWidth() {
        assertEquals(PinchLine(540, 1200, 216, 864), GesturePaths.limit(screen, 540, 1200, out = true))
        assertEquals(PinchLine(540, 1200, 864, 216), GesturePaths.limit(screen, 540, 1200, out = false))
    }

    @Test
    fun aPinchNearASideEdgeKeepsBothFingersOnTheScreen() {
        // ScreenActions.pinch puts the fingers half a span to each side of the centre: here at last from 0 to 200, and from
        // 921 to 1079, the screen's last column.
        assertEquals(PinchLine(100, 1200, 50, 200), GesturePaths.limit(screen, 100, 1200, out = true))
        assertEquals(PinchLine(1000, 1200, 39, 158), GesturePaths.limit(screen, 1000, 1200, out = true))
    }

    @Test
    fun aPinchOnASideEdgeOrOutsideTheScreenHasNoPath() {
        assertNull(GesturePaths.limit(screen, 0, 1200, out = true))
        assertNull(GesturePaths.limit(screen, 1080, 1200, out = true))
        assertNull(GesturePaths.limit(screen, 540, -1, out = false))
    }

    @Test
    fun aSwipeCoversAThirdOfTheScreenInTheFingersDirection() {
        assertEquals(SwipeLine(540, 1200, 540, 400), GesturePaths.limit(screen, 540, 1200, ScrollDirection.UP))
        assertEquals(SwipeLine(540, 1200, 540, 2000), GesturePaths.limit(screen, 540, 1200, ScrollDirection.DOWN))
        assertEquals(SwipeLine(540, 1200, 180, 1200), GesturePaths.limit(screen, 540, 1200, ScrollDirection.LEFT))
        assertEquals(SwipeLine(540, 1200, 900, 1200), GesturePaths.limit(screen, 540, 1200, ScrollDirection.RIGHT))
    }

    @Test
    fun aSwipeNearAnEdgeEndsOnePixelInsideTheScreen() {
        assertEquals(SwipeLine(540, 2300, 540, 2398), GesturePaths.limit(screen, 540, 2300, ScrollDirection.DOWN))
        assertEquals(SwipeLine(100, 1200, 1, 1200), GesturePaths.limit(screen, 100, 1200, ScrollDirection.LEFT))
        // A swipe that cannot move, or that starts outside the screen, has no path.
        assertNull(GesturePaths.limit(screen, 540, 1, ScrollDirection.UP))
        assertNull(GesturePaths.limit(screen, 540, 2400, ScrollDirection.UP))
    }

    @Test
    fun anEdgeSwipeStartsOnePixelInsideItsEdgeAndGoesAThirdInwards() {
        assertEquals(SwipeLine(540, 1, 540, 801), GesturePaths.fromEdge(screen, ScreenEdge.TOP))
        assertEquals(SwipeLine(540, 2398, 540, 1598), GesturePaths.fromEdge(screen, ScreenEdge.BOTTOM))
        assertEquals(SwipeLine(1, 1200, 361, 1200), GesturePaths.fromEdge(screen, ScreenEdge.LEFT))
        assertEquals(SwipeLine(1078, 1200, 718, 1200), GesturePaths.fromEdge(screen, ScreenEdge.RIGHT))
    }
}
