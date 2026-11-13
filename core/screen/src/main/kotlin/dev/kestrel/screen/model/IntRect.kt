package dev.kestrel.screen.model

import kotlinx.serialization.Serializable

/** A rectangle in screen pixels. As in Android's `Rect`, [right] and [bottom] lie just outside it. */
@Serializable
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /** The part of this rectangle that lies inside [other], or null when they do not overlap. */
    fun intersect(other: IntRect): IntRect? {
        val overlap = IntRect(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
        return overlap.takeUnless { it.isEmpty }
    }
}
