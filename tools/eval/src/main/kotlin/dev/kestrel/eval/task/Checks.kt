package dev.kestrel.eval.task

import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookAnswer

/**
 * The front app's elements that match, counted on the phone by the hook screen-has (design D4 of sub-project 6): the
 * count must lie in [count]. [label] and [contains] may hold the run's placeholders, such as "{marker}".
 */
data class ScreenHas(
    val packageName: String? = null,
    val label: String? = null,
    val contains: String? = null,
    val state: String? = null,
    val className: String? = null,
    val count: IntRange = 1..Int.MAX_VALUE,
) : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val found = scope.hooks.screenHas(packageName, label?.let { scope.values.fill(it) }, contains?.let { scope.values.fill(it) }, className, state)
        return found.int("count") in count
    }
}

/** The app in front is one of [packages], from `dumpsys activity activities`. */
data class Foreground(val packages: Set<String>) : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean =
        Dumps.resumedPackage(scope.device.read("dumpsys activity activities")) in packages
}

/** Test mode's recogniser takes the chat on the screen for a self-chat (hook selfchat-check). */
data object SelfChat : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean = scope.hooks.selfchatCheck()
}

/** The task confirmed between [min] and [max] sends, calls or deletes (agent-task's `confirmed`). */
data class Confirmed(val min: Int = 0, val max: Int = Int.MAX_VALUE) : Check {
    override val needsTask: Boolean get() = true

    override fun passes(scope: RunScope, answer: HookAnswer): Boolean = answer.int("confirmed") in min..max
}

/** The task shared exactly [items] items (agent-task's `shareItems`). */
data class ShareItems(val items: Int) : Check {
    override val needsTask: Boolean get() = true

    override fun passes(scope: RunScope, answer: HookAnswer): Boolean = answer["shareItems"]?.toIntOrNull() == items
}

/** A confirmed recipient of the task is [name], compared on the phone (agent-task -Recipient answers match, other or none). */
data class Recipient(val name: String) : Check {
    override val needsTask: Boolean get() = true

    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val tag = scope.tag ?: return false
        return scope.hooks.agentTask(tag, recipient = name)["recipient"] == "match"
    }
}

/** Kestrel's spoken reply holds the run's marker, compared on the phone (agent-task -Marker answers 1 or 0). */
data object ReplyHas : Check {
    override val needsTask: Boolean get() = true

    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val tag = scope.tag ?: return false
        return scope.hooks.agentTask(tag, marker = scope.values.marker)["marker"] == "1"
    }
}

/**
 * One number of Kestrel's spoken reply holds [digits] in a row, from the start of one of its groups of digits to the end
 * of one, compared on the phone (agent-task -Digits answers 1 or 0). Groups belong to one number when only spaces,
 * hyphens, dots, brackets or `+` stand between them, so "+1 202-555-0143" holds 2025550143, but "58 percent, about 7
 * hours" does not hold 587. [digits] may hold the run's placeholders, such as "{number}".
 */
data class ReplyHasDigits(val digits: String) : Check {
    override val needsTask: Boolean get() = true

    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val tag = scope.tag ?: return false
        return scope.hooks.agentTask(tag, digits = scope.values.fill(digits).filter { it.isDigit() })["digits"] == "1"
    }
}
