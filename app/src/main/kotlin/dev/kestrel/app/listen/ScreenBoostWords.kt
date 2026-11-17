package dev.kestrel.app.listen

import dev.kestrel.screen.ScreenSession

/**
 * The labels on the screen as boost words of an utterance (design spec, section 8.3), from the newest snapshot that
 * [screen]'s session has built. It never builds a snapshot, so the recogniser never waits for the screen;
 * `ScreenSession.keepFresh` keeps that snapshot current while Kestrel listens. Without Kestrel's accessibility service
 * there are no labels. Never log a boost word.
 */
internal class ScreenBoostWords(private val screen: () -> ScreenSession?) {
    fun words(): List<String> = screen()?.snapshots?.latest()?.snapshot?.let(BoostWordBudget::screenNames).orEmpty()
}
