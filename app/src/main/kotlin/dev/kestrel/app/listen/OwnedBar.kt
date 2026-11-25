package dev.kestrel.app.listen

/** Who put the text on the feedback bar that it shows last (design 6.6 of sub-project 7). */
internal sealed interface BarOwner {
    /**
     * A text that nobody takes back: a mode, a transcript, "Microphone blocked", a failure, and what the conversation says,
     * asks or hands over. A question or a hand-over's sentence stays until the next text.
     */
    data object Other : BarOwner

    /** The instant commands' texts: the command that keeps one releases it when it ends (section 7.3). */
    data object Commands : BarOwner

    /** The conversation's final reply number [reply], over which its follow-up window counts down. */
    data class FinalReply(val reply: Int) : BarOwner

    /** The follow-up window's countdown, "Go on…" with the seconds left. */
    data object FollowUp : BarOwner
}

/**
 * The feedback bar as Listening shares it: it knows whose text it shows last, so that a text is taken over only from the
 * owner that it follows and released only by its own owner (design 6.6 of sub-project 7). The bar is free once its text
 * was released. Any thread may call it; the calls reach [bar] in their order.
 */
internal class OwnedBar(private val bar: FeedbackBar) {
    // Null while the bar is free.
    private var owner: BarOwner? = null

    /** Shows [text] for [owner], as [FeedbackBar.show] does. */
    @Synchronized
    fun show(text: String, keep: Boolean = false, owner: BarOwner = BarOwner.Other) {
        this.owner = owner
        bar.show(text, keep)
    }

    /**
     * Keeps [text] on the bar for [owner], but only while the bar is free, shows [owner]'s own text, or shows the text of
     * [over]; otherwise it shows nothing, so that it never hides a text that someone showed since, and returns false.
     */
    @Synchronized
    fun takeOver(text: String, owner: BarOwner, over: BarOwner): Boolean {
        val current = this.owner
        if (current != null && current != owner && current != over) return false
        this.owner = owner
        bar.show(text, keep = true)
        return true
    }

    /** Lets [owner]'s kept text fade from now on, if the bar still shows it; another owner's text stays. */
    @Synchronized
    fun release(owner: BarOwner) {
        if (this.owner != owner) return
        this.owner = null
        bar.release()
    }
}
