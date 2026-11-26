package dev.kestrel.screen.overlay

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.read.ScreenChangeTracker

/**
 * Whether the question card counts as shown, for any thread (design D8 and D32 item 20 of sub-project 5). After the
 * card has ended, Android removes its window only on a later main-thread message, and the input system forgets it a frame
 * after that, so a touch there could still reach the card. The card therefore counts as shown, with its last bounds,
 * until [CLOSING_MILLIS] after it ended: `ScreenActions` refuses to act and avoids its bounds until then, and
 * `AppScreenPort` waits that one settle more before it reads or acts.
 */
internal class CardPresence(private val clock: () -> Long) {
    @Volatile
    private var open = false

    @Volatile
    private var lastBounds: IntRect? = null

    /** When the last card ended, or null before the first one. */
    @Volatile
    var closedAt: Long? = null
        private set

    val isShowing: Boolean get() = open || closedAt?.let { clock() - it < CLOSING_MILLIS } == true

    /** The card's bounds once Android has laid it out, also while it closes; null while no card counts as shown. */
    val bounds: IntRect? get() = lastBounds?.takeIf { isShowing }

    /** A new card is on the screen; its bounds come with [laidOut]. */
    fun shown() {
        lastBounds = null
        open = true
    }

    fun laidOut(bounds: IntRect) {
        lastBounds = bounds
    }

    fun closed() {
        // The time goes first, so that a reader on another thread never sees the card neither open nor closing.
        closedAt = clock()
        open = false
    }

    companion object {
        /** One settle after the card ended (design D8 of sub-project 5). */
        const val CLOSING_MILLIS = ScreenChangeTracker.ACTION_QUIET_MILLIS
    }
}
