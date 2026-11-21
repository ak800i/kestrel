package dev.kestrel.safety

import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTree

/** What the check before a send found: it passed, or a field shows other text than the task typed, or the flow left its app. */
sealed interface SendCheckResult {
    data object Passed : SendCheckResult

    /** [fieldText] is the raw value of the field, which the agent redacts, quotes and escapes before the model sees it. */
    data class Failed(val fieldText: String, val wrongApp: Boolean) : SendCheckResult {
        override fun toString(): String = "Failed(fieldTextLength=${fieldText.length}, wrongApp=$wrongApp)"
    }
}

/**
 * The cheap form of Coati's check before a commit (design D14 of sub-project 5; research r61): before a tap on a send,
 * save, done, apply or OK control, or on the unlabelled button beside a message field, Kestrel reads a fresh snapshot and
 * checks that every text that the task typed into a field of the current screen is still that field's value (after
 * trimming), except in a search, URL or address field, which an app may clear after it found something, and that a share
 * flow is still in the app that it opened. Nothing is tapped when the check fails; no model is called.
 */
object SendCheck {
    private val PRESSING = setOf(ActionKind.TAP, ActionKind.LONG_PRESS, ActionKind.TAP_AT)

    /**
     * Whether [proposal] presses a control whose label holds a send word or save, done, apply or OK, or the unlabelled
     * button beside a message field, the most common send arrow (review 2 M2); for a `TapAt`, the element under its point.
     */
    fun applies(proposal: Proposal, screen: ScreenSnapshot): Boolean {
        if (proposal.kind !in PRESSING) return false
        val index = proposal.targetKey?.let { key -> screen.elements.indexOfFirst { it.key == key }.takeIf { it >= 0 } }
        val point = proposal.point.takeIf { proposal.targetKey == null }
        val pressed = ContextRules.pressed(screen, index, point)
        if (pressed != null && ContextRules.nearCompose(screen, pressed, point)) return true
        val names = pressed?.let { ContextRules.clickedNames(screen, it) }.orEmpty() + proposal.pointText
        return names.any { LabelWords.holds(it, Category.SENDING) || LabelWords.holdsWord(it, LabelWords.SAVE_WORDS) }
    }

    fun check(proposal: Proposal, screen: ScreenSnapshot, task: TaskSafety): SendCheckResult {
        for (typed in task.typed) {
            val field = screen.elements.firstOrNull { it.key == typed.fieldKey && it.editable }
                ?: screen.elements.firstOrNull {
                    it.editable && it.packageName == typed.packageName && typed.fieldName != null &&
                        (it.hint ?: it.contentDescription ?: it.label) == typed.fieldName
                }
                ?: continue
            if (ContextRules.notMessageField(field)) continue
            val shown = field.text.orEmpty()
            if (shown.trim() != typed.text.trim()) return SendCheckResult.Failed(shown, wrongApp = false)
        }
        val flow = task.shareFlow
        val front = ScreenTree.frontWindow(screen)?.packageName
        if (flow != null && front != flow.receivingPackage && front !in ContextRules.SHARE_SHEETS) return SendCheckResult.Failed("", wrongApp = true)
        return SendCheckResult.Passed
    }
}
