package dev.kestrel.app.agent.eyes

import dev.kestrel.agent.port.OcrLine
import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

/** A line that two partly overlapping OCR regions both read reaches the agent once (design D20 of sub-project 5). */
class AppEyesTest {
    @Test
    fun aLineReadInTwoOverlappingRegionsComesOnceWithTheHigherScore() {
        val first = OcrLine("Pay now", IntRect(100, 200, 260, 240), 0.91)
        val second = OcrLine("Pay now", IntRect(102, 201, 261, 240), 0.95)
        val below = OcrLine("Cancel", IntRect(100, 300, 220, 340), 0.9)

        assertEquals(listOf(second, below), AppEyes.once(listOf(first, second, below)))
    }

    @Test
    fun aLineCutByARegionsEdgeGivesWayToTheWholeLine() {
        val whole = OcrLine("Pay now", IntRect(100, 200, 260, 240), 0.9)
        val cut = OcrLine("Pay", IntRect(100, 200, 150, 240), 0.99)

        assertEquals(listOf(whole), AppEyes.once(listOf(cut, whole)))
    }

    @Test
    fun linesInOtherPlacesOrWithOtherTextsStay() {
        val one = OcrLine("Delete", IntRect(100, 200, 200, 240), 0.9)
        val two = OcrLine("Delete", IntRect(100, 400, 200, 440), 0.9)
        val other = OcrLine("Keep", IntRect(110, 205, 190, 240), 0.9)
        val corner = OcrLine("Delete", IntRect(180, 230, 280, 270), 0.9)

        assertEquals(listOf(one, other, corner, two), AppEyes.once(listOf(one, other, corner, two)))
    }
}