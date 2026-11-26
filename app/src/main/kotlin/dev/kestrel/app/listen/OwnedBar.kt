package dev.kestrel.app.listen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Who put the text on the feedback bar that it shows last (design 6.6 of sub-project 7). */
internal sealed interface BarOwner {
    /**
     * A text that nobody takes back: a mode, a transcript, "Microphone blocked", a failure, and what the conversation says,
     * asks or hands over. A question or a hand-over's sentence stays until the next text, or until its channel lets it go.
     */
    data object Other : BarOwner

    /** The instant commands' texts: the command that keeps one releases it when it ends (section 7.3). */
    data object Commands : BarOwner

    /** The conversation's final reply number [reply], over which its follow-up window counts down. */
    data class FinalReply(val reply: Int) : BarOwner

    /** The follow-up window's countdown, "Go on…" with the seconds left. */
    data object FollowUp : BarOwner

    /** A goal's progress, design 10.5's "Step 12 · 3 of 7 · …" or "On hold", which only ever replaces progress (plan 7F). */
    data object Progress : BarOwner
}

/**
 * Kestrel's feedback bar with its one writer (design 6.6 and 10.5 of sub-project 7): listening's texts, the text channel's
 * words, the owner's parts' release of a question's text and a goal's progress all reach [bar] through it, in the order of
 * the calls, and it knows whose text the bar shows last, so that a text is taken over only from the owner that it follows
 * and released only by its own owner. The bar is free once its text was released. A goal's [progress] never hides another
 * owner's text: while a kept text or a final reply, which its follow-up countdown takes over, holds the bar, or another
 * text showed less than [OTHERS_MILLIS] ago on [clock], the bar's own time before it fades, the newest progress waits, and it shows once the kept text is released or that time has passed,
 * in [scope]. So "On hold" shows after the held goal's own sentence, and a progress replaces only progress (plan 7F). Any
 * thread may call it.
 */
internal class OwnedBar(
    private val bar: FeedbackBar,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val lock = Any()

    // Guarded by lock. The owner is null while the bar is free.
    private var owner: BarOwner? = null
    private var kept = false
    private var shownAt: Long? = null
    private var pending: String? = null
    private var waiting: Job? = null

    /** Shows [text] for [owner], as [FeedbackBar.show] does. */
    fun show(text: String, keep: Boolean = false, owner: BarOwner = BarOwner.Other) {
        synchronized(lock) { put(text, keep, owner) }
    }

    /**
     * Keeps [text] on the bar for [owner], but only while the bar is free, shows [owner]'s own text, or shows the text of
     * [over]; otherwise it shows nothing, so that it never hides a text that someone showed since, and returns false.
     */
    fun takeOver(text: String, owner: BarOwner, over: BarOwner): Boolean {
        synchronized(lock) {
            val current = this.owner
            if (current != null && current != owner && current != over) return false
            put(text, keep = true, owner)
            return true
        }
    }

    /** Lets [owner]'s kept text fade from now on, if the bar still shows it; another owner's text stays. */
    fun release(owner: BarOwner) {
        synchronized(lock) {
            if (this.owner != owner) return
            this.owner = null
            kept = false
            bar.release()
            flush()
        }
    }

    /**
     * Shows a goal's [text] once no other owner's text holds the bar; a newer progress replaces one that still waits, and
     * null drops it, because its goal has ended or asks (review L1 of plan 7F). A line that already shows fades by itself.
     */
    fun progress(text: String?) {
        synchronized(lock) {
            pending = text
            if (text == null) {
                waiting?.cancel()
                waiting = null
            }
            flush()
        }
    }

    override fun toString(): String = synchronized(lock) { "OwnedBar(owner=$owner, kept=$kept, pending=${pending != null})" }

    // Under the lock.
    private fun put(text: String, keep: Boolean, owner: BarOwner) {
        this.owner = owner
        kept = keep
        shownAt = clock()
        bar.show(text, keep)
    }

    // Under the lock: the waiting progress now, when the bar is free or shows progress, or once the other text's time has
    // passed. A final reply holds the bar as a kept text does, until its follow-up window's countdown has taken it over and
    // let go, or another text replaced it, so that a progress never cuts the countdown (review A-M5 of plan 7F); a goal's
    // progress and a follow-up window never meet otherwise, because the window opens only without a goal (plan 7D).
    private fun flush() {
        val text = pending ?: return
        if (kept || owner is BarOwner.FinalReply) return
        val left = if (owner == BarOwner.Progress) 0L else shownAt?.let { OTHERS_MILLIS - (clock() - it) } ?: 0L
        if (left <= 0) {
            pending = null
            put(text, keep = false, BarOwner.Progress)
        } else if (waiting?.isActive != true) {
            waiting = scope.launch {
                delay(left)
                synchronized(lock) {
                    waiting = null
                    flush()
                }
            }
        }
    }

    companion object {
        /** How long the bar shows a text that is not kept before it fades (`FeedbackBar.FADE_AFTER_MILLIS` of `:core:screen`). */
        const val OTHERS_MILLIS = 4_000L
    }
}
