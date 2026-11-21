package dev.kestrel.agent.loop

import dev.kestrel.agent.observe.ObservationTable
import dev.kestrel.agent.tools.AgentCall
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTitle
import dev.kestrel.screen.model.ScreenTree

/**
 * The result of [CompletionCheck.check]: passed; what was missing, which the model gets as "finish failed: …"; or
 * evidence that Kestrel cannot check (only OCR lines), which ends the task as unverified (D21).
 */
sealed interface Verdict {
    data object Passed : Verdict

    data object Unverifiable : Verdict

    data class Missing(val what: String) : Verdict {
        override fun toString(): String = "Missing(length=${what.length})"
    }
}

/**
 * Spec 11.8's completion check (design D21; r61): `finish` must cite evidence, and Kestrel checks it on a fresh snapshot
 * taken after the settle wait. A cited element of the newest observation ([table]) must still exist with the same key and
 * label, or an element with the same label and class in the same app; a cited checkable element must show the state that
 * its evidence names, or else the state that the task's own last action on it produced ([lastChecked]), and one of the two
 * must exist; a cited step must be one of [steps]: the task's data steps whose result was `ok` and the steps that the
 * owner did by hand after a hand-over. OCR lines cannot be checked; evidence made only of them is [Verdict.Unverifiable].
 * Every confirmed send needs its own evidence, also after a hand-over: a cited element that holds the sent text outside
 * an input field, or after a share, a screen whose title is the confirmed recipient with a cited element of the shared
 * items (review B M14).
 */
internal object CompletionCheck {
    fun check(
        finish: AgentCall.Finish,
        table: ObservationTable,
        fresh: ScreenSnapshot,
        appLabel: String?,
        steps: Set<Int>,
        sends: List<ConfirmedSend>,
        lastChecked: Map<String, Boolean>,
    ): Verdict {
        if (finish.evidence.isEmpty()) return Verdict.Missing("no evidence was cited")
        val cited = mutableListOf<String>()
        // The fresh elements that the evidence cites, by index.
        val shown = mutableListOf<Int>()
        var media = false
        var checkable = false
        for (evidence in finish.evidence) {
            val step = evidence.step
            val target = evidence.target
            if (step != null) {
                if (step !in steps) return Verdict.Missing("step $step is not a data step of this task with a result")
                checkable = true
            }
            if (target == null) continue
            val entry = table.entry(target) ?: return Verdict.Missing("($target) is not in the newest observation")
            val key = entry.key
            if (entry.ocr || key == null) continue
            checkable = true
            val element = fresh.element(key)?.takeIf { it.label == entry.label && it.className == entry.className }
                ?: fresh.elements.firstOrNull { it.label == entry.label && it.className == entry.className && it.packageName == entry.packageName }
                ?: return Verdict.Missing("($target) is no longer on the screen")
            if (element.checkable) {
                val wanted = evidence.checked ?: lastChecked[key] ?: return Verdict.Missing("say which state ($target) should show")
                if (element.checked != wanted) return Verdict.Missing("($target) is ${if (element.checked) "checked" else "not checked"}")
            }
            val text = listOfNotNull(element.label, element.text, element.contentDescription).joinToString(" ")
            cited += text
            fresh.elements.indexOf(element).takeIf { it >= 0 }?.let(shown::add)
            media = media || MEDIA_CLASSES.any { element.className?.endsWith(it) == true } || Normalizer.words(text).any { it in MEDIA_WORDS }
        }
        if (!checkable) return Verdict.Unverifiable
        val front = ScreenTree.frontWindow(fresh)
        val title = front?.let { ScreenTitle.of(fresh, it, appLabel, withRule3 = false) }
        for (send in sends) {
            val shown = if (send.share || send.content.isNullOrBlank()) {
                title != null && send.recipients.any { Normalizer.normalize(it) == Normalizer.normalize(title) } && (media || !send.share && cited.isNotEmpty())
            } else {
                val content = Normalizer.normalize(send.content)
                shown.any { holdsSent(fresh, it, content) }
            }
            if (!shown) return Verdict.Missing(if (send.share) "the chat it was shared to, with the shared items, is not on the screen" else "no cited element shows the sent message")
        }
        return Verdict.Passed
    }

    /**
     * Whether the element at [index] shows the sent [content] (folded): a text that is still in an input field is not sent
     * (a failed or doubted Send leaves it there), so an editable element is no evidence, and a container counts only
     * by the text that remains when its editable descendants' texts are taken out of its label (D21).
     */
    private fun holdsSent(screen: ScreenSnapshot, index: Int, content: String): Boolean {
        val element = screen.elements[index]
        if (element.editable) return false
        val fields = screen.elements.indices
            .filter { screen.elements[it].editable && index in ScreenTree.ancestors(screen, it) }
            .flatMap { listOfNotNull(screen.elements[it].text, screen.elements[it].label) }
            .filter { it.isNotBlank() }
        val label = fields.fold(element.label.orEmpty()) { rest, field -> rest.replace(field, " ") }
        val own = listOfNotNull(element.text?.takeIf { fields.isEmpty() }, element.contentDescription, label).joinToString(" ")
        return Normalizer.normalize(own).contains(content)
    }

    private val MEDIA_CLASSES = listOf("ImageView", "VideoView")
    private val MEDIA_WORDS = setOf(
        "photo", "photos", "video", "videos", "image", "images", "picture", "pictures",
        "fotografija", "fotografije", "slika", "slike", "snimak", "snimci",
    )
}
