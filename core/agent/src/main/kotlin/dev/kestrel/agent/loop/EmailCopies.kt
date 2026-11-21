package dev.kestrel.agent.loop

import dev.kestrel.agent.port.Expect
import dev.kestrel.agent.port.Press
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTree

/**
 * An e-mail's collapsed Cc and Bcc (design D14): before Kestrel asks about an e-mail, it opens them, so that the question
 * names every recipient. `ConfirmationBuilder` reads only what the screen shows, so the loop taps the expander, after the
 * safety check, like every other tap.
 */
internal object EmailCopies {
    /** The e-mail apps whose expander Kestrel knows; plan 5B adds the packages that it records. */
    val EMAIL_APPS = setOf("com.google.android.gm")

    private val COPY_FIELDS = setOf("cc", "bcc")
    private val EXPANDER_WORDS = setOf("cc", "bcc", "show", "add")

    /**
     * The tap that opens Cc and Bcc, or null: the front window of an e-mail app shows a To field and no Cc or Bcc field,
     * and exactly one clickable element that is no field has a name made only of Cc and Bcc words ("Show Cc/Bcc").
     */
    fun expand(screen: ScreenSnapshot): ScreenAction.Tap? {
        val window = ScreenTree.frontWindow(screen)?.takeIf { it.packageName in EMAIL_APPS } ?: return null
        val shown = screen.elements.filter { it.windowId == window.id }
        if (shown.none { it.editable && field(it) == "to" } || shown.any { it.editable && field(it) in COPY_FIELDS }) return null
        val expander = shown.singleOrNull { it.clickable && !it.editable && expands(ScreenTree.name(it)) } ?: return null
        val expect = Expect(expander.label, expander.className, expander.packageName, editable = false, password = false, checkable = expander.checkable)
        return ScreenAction.Tap(expander.key, expect, Press.VERIFIED)
    }

    private fun expands(name: String?): Boolean {
        val words = Normalizer.words(name.orEmpty())
        return words.isNotEmpty() && words.all { it in EXPANDER_WORDS } && words.any { it in COPY_FIELDS }
    }

    private fun field(element: ScreenElement): String? = (ScreenTree.idName(element.viewId) ?: element.hint ?: element.label)?.trim()?.lowercase()
}
