package dev.kestrel.app.debug

import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.app.agent.AgentRunner
import dev.kestrel.app.orchestrator.TaskAsk
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.Category
import dev.kestrel.safety.EmergencyGuard
import dev.kestrel.safety.LabelWords
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTree
import java.util.Locale

/**
 * The answers of the agent's debug hooks (design D28 of sub-project 5; plan-5b-interfaces.md, section 4), as plain
 * functions: one line of `name=value` pairs with no spaces inside a value, and never screen text, a prompt, a reply, a
 * name or a tool's arguments. The comparisons of a recipient and a marker run here, on the device, and answer only match
 * or a count.
 */
internal object AgentHookAnswers {
    /**
     * agent-task -Result's answer for a task that [ended]: its report's end and counts; `marker=1` when the sentence that
     * Kestrel said at the end or one of its `say` texts holds [marker]'s words in order, because the owner hears both
     * (design D29); the owner's touches that the watcher reported (`ownerTouches=`) and the signals among them
     * (`signals=`); with [marker] also `markerSaid=1|0`, whether a `say` text held it, and `markerInData=1|0|none`, whether
     * a data tool's result held the run's marker (none when the task had none); and with [digits] (agent-task -Digits),
     * `digits=1|0` at its end, for the end's sentence and the `say` texts alike (plan 6B-basefix).
     */
    fun result(ended: AgentRunner.Ended, marker: String?, recipient: String?, digits: String? = null): String {
        val report = ended.report
        val said = ended.evidence.said
        val texts = said + listOfNotNull(ended.evidence.spoken)
        val inData = ended.evidence.markerInData?.let { seen -> if (seen) "1" else "0" } ?: "none"
        return "state=done end=${report.end} steps=${report.steps} workMs=${report.workMillis} " +
            "credits=${String.format(Locale.ROOT, "%.2f", report.credits)} confirmed=${report.confirmed} " +
            "unconfirmed=${report.unconfirmed} shareItems=${report.shareItems} recipient=${recipient(report.recipients, recipient)} " +
            "marker=${anyOf(texts) { marker(it, marker) }} beforeTestMode=${report.beforeTestMode} model=${word(report.modelId)} " +
            "in=${report.inputTokens} out=${report.outputTokens} cacheRead=${report.cacheRead} cacheWrite=${report.cacheWrite} " +
            "ownerTouches=${ended.touches} signals=${ended.signals}" +
            (marker?.let { wanted -> " markerSaid=${anyOf(said) { marker(it, wanted) }} markerInData=$inData" } ?: "") +
            (digits?.let { wanted -> " digits=${anyOf(texts) { digits(it, wanted) }}" } ?: "")
    }

    // 1 when one of [texts] gives 1, else 0; each text is compared alone, so that no match spans two of them.
    private fun anyOf(texts: List<String>, test: (String) -> Int): Int = if (texts.any { test(it) == 1 }) 1 else 0

    /**
     * 1 when one number of the spoken reply holds the digits of [digits] in a row, from the start of one of its groups of
     * ASCII digits to the end of one, else 0 (design D17 of sub-project 6): a phone number or a battery level said in any
     * format, never the reply itself. Groups belong to one number when only [NUMBER_SEPARATORS] stand between them, so
     * "+1 202-555-0143" holds 2025550143, but "58 percent, about 7 hours" does not hold 587 and 187 does not hold 87.
     */
    fun digits(spoken: String, digits: String?): Int {
        val wanted = digits.orEmpty().filter { it in '0'..'9' }
        if (wanted.isEmpty()) return 0
        return if (numbers(spoken).any { it.holds(wanted) }) 1 else 0
    }

    private val NUMBER_SEPARATORS = setOf(' ', '\u00A0', '-', '\u2010', '\u2011', '.', '(', ')', '[', ']', '+')

    /** A number's digits with the offsets in them where its groups start and end. */
    private class SpokenNumber(val digits: String, val starts: Set<Int>, val ends: Set<Int>) {
        fun holds(wanted: String): Boolean = starts.any { start ->
            ends.contains(start + wanted.length) && digits.startsWith(wanted, start)
        }
    }

    private fun numbers(spoken: String): List<SpokenNumber> {
        val numbers = mutableListOf<SpokenNumber>()
        var digits = StringBuilder()
        var starts = mutableSetOf<Int>()
        var ends = mutableSetOf<Int>()
        fun close() {
            if (digits.isNotEmpty()) numbers += SpokenNumber(digits.toString(), starts, ends)
            digits = StringBuilder()
            starts = mutableSetOf()
            ends = mutableSetOf()
        }
        var inGroup = false
        for (char in spoken) {
            when {
                char in '0'..'9' -> {
                    if (!inGroup) starts += digits.length
                    digits.append(char)
                    inGroup = true
                }
                char in NUMBER_SEPARATORS -> {
                    if (inGroup) ends += digits.length
                    inGroup = false
                }
                else -> {
                    if (inGroup) ends += digits.length
                    inGroup = false
                    close()
                }
            }
        }
        if (inGroup) ends += digits.length
        close()
        return numbers
    }

    /**
     * The elements of the front window that screen-tap may press, in the snapshot's order: those whose label is [label], or
     * with [contains] those whose label holds that text without letter case (design D17 of sub-project 6).
     */
    fun tapMatches(snapshot: ScreenSnapshot, label: String?, contains: String?): List<Int> {
        val front = ScreenTree.frontWindow(snapshot) ?: return emptyList()
        return snapshot.elements.indices.filter { index ->
            val element = snapshot.elements[index]
            val text = element.label
            element.windowId == front.id && text != null &&
                if (contains != null) text.contains(contains, ignoreCase = true) else text == label
        }
    }

    /** network-allowlist's list of host names or patterns, sorted and joined with commas; `none` for an empty list. */
    fun hostList(hosts: Collection<String>): String = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString(",").ifEmpty { "none" }

    /**
     * agent-state's `question=`: the kind that the agent asked the open question with, `yes-no`, `free` (the model's
     * `ask_user`, or a routine's name), `pick-number` (look_closer's "Which number should I use?") or `screenshot`
     * (look_closer's screenshot question, which the Orchestrator asks as a free question), so that :tools:eval answers each
     * as the owner decided (plan 6B-basefix).
     */
    fun question(kind: AnswerKind): String = kind.name.lowercase().replace('_', '-')

    /** Whether one of the task's confirmed [recipients] is [wanted], compared folded: match, other, or none without either. */
    fun recipient(recipients: List<String>, wanted: String?): String = when {
        wanted.isNullOrBlank() || recipients.isEmpty() -> "none"
        recipients.any { Normalizer.normalize(it) == Normalizer.normalize(wanted) } -> "match"
        else -> "other"
    }

    /** 1 when the spoken reply holds the marker's words in order, else 0 (design D29). */
    fun marker(spoken: String, marker: String?): Int = if (marker != null && MarkerWatch.holds(spoken, marker)) 1 else 0

    /**
     * The words that agent-answer says for [answer], as the owner's voice would: yes, no, confirm, cancel, continue, a
     * number, or `text:<words>`; null for anything else.
     */
    fun words(answer: String): String? = when {
        answer in SPOKEN -> answer
        answer.matches(Regex("\\d{1,3}")) -> answer
        answer.startsWith("text:") && answer.length > 5 -> answer.substring(5)
        else -> null
    }

    /**
     * Why agent-answer may not send [words] to the open question of kind [asking], whose confirmation, if any, has the
     * category [open], when the script read the category [expected] (a wire name) with agent-state; null when it may
     * (design D28; task-5b-20-review M1). With [expected], the open question must be a confirmation of exactly that
     * category, so that an answer meant for one confirmation never reaches the next one. Without it, a confirmation takes
     * only "no" and "cancel", so that no word that confirms reaches a confirmation whose category the script did not read.
     */
    fun answerRefusal(asking: TaskAsk?, words: String, open: Category?, expected: String?): String? = when {
        expected != null && (asking != TaskAsk.CONFIRM || open?.wire != expected) -> "the task asks no confirmation of $expected now"
        expected == null && asking == TaskAsk.CONFIRM && words !in DECLINING -> "add --es confirming with the open confirmation's category"
        else -> null
    }

    /**
     * agent-memory -Has's answer: yes when one of the facts' [keys] is [key], compared folded as the memory store compares
     * keys, else no; never a value (design D17 of sub-project 6).
     */
    fun has(keys: List<String>, key: String): String = if (keys.any { Normalizer.normalize(it) == Normalizer.normalize(key) }) "yes" else "no"

    /** The aliases of agent-settings' `pkg=pkg,pkg=pkg`, which map a fixture's package to an app's self-chat rule. */
    fun aliases(text: String): Map<String, String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate { pair ->
        val parts = pair.split('=')
        require(parts.size == 2 && parts.all { PACKAGE.matches(it.trim()) }) { "add --es selfChatAliases as pkg=pkg,pkg=pkg" }
        parts[0].trim() to parts[1].trim()
    }

    /**
     * What agent-tree keeps of an element's label (design D28; review C I10): the label only when it is one of the [safe]
     * strings (the self-chats' fixed texts and the owner's configured names) or, in SystemUI, a Quick Settings tile's label
     * of [TILES]; otherwise null, and the tree keeps only its length and [status].
     */
    fun safeLabel(label: String?, packageName: String?, safe: Set<String>): String? = when {
        label == null -> null
        packageName == SYSTEM_UI -> label.takeIf { it in TILES }
        label in safe -> label
        else -> null
    }

    /** Whether a label reads as a chat's status: last-seen, online, typing, members, or none. */
    fun status(label: String?): String {
        val text = label?.lowercase().orEmpty()
        return when {
            "last seen" in text -> "last-seen"
            "online" in text -> "online"
            "typing" in text -> "typing"
            "member" in text -> "members"
            else -> "none"
        }
    }

    /**
     * Whether screen-tap refuses to press element [index] of [snapshot] for the requested [label] (process rule 10): what
     * the agent itself would never press without the owner's confirmation or at all. That is anything while Kestrel's own
     * app, a phone app of [phonePackages] (with the default phone app) or a contacts app is in front, as the safety check's
     * call rules read them; and in any app, SystemUI's emergency controls included, a label, text or content description
     * of the element or of a clickable ancestor that holds a word of [Category.CALLS] ([LabelWords]) or "emergency" or
     * "SOS" ([EmergencyGuard]), and the requested label itself with such a word.
     */
    fun tapRefused(snapshot: ScreenSnapshot, index: Int?, label: String, phonePackages: Set<String>): Boolean {
        val front = ScreenTree.frontWindow(snapshot)?.packageName
        if (front == AgentFreeApps.KESTREL || front in phonePackages || front in EmergencyGuard.CONTACTS_PACKAGES) return true
        if (callOrEmergency(label)) return true
        if (index == null) return false
        val reached = listOf(index) + ScreenTree.ancestors(snapshot, index).filter { snapshot.elements[it].clickable }
        return reached.any { at ->
            val element = snapshot.elements[at]
            // emergencyNamed reads the view ID too, such as SystemUI's "emergency_button".
            EmergencyGuard.emergencyNamed(snapshot, at) || listOf(element.label, element.text, element.contentDescription).any(::callOrEmergency)
        }
    }

    private fun callOrEmergency(text: String?): Boolean = LabelWords.holds(text, Category.CALLS) || EmergencyGuard.namesEmergency(text)

    /** The self-chats' fixed texts that agent-tree may keep (design D28). */
    val FIXED_SAFE = setOf("Saved Messages", "forward here to save", "Forward here to save.", "Message yourself", "My Notes")

    /** The labels of Quick Settings tiles that agent-tree may keep in SystemUI's windows. */
    val TILES = setOf(
        "Internet", "Wi-Fi", "Bluetooth", "Hotspot", "Mobile data", "Airplane mode", "Flashlight", "Location",
        "Do Not Disturb", "Auto-rotate", "Battery Saver", "Dark theme", "Mobile Hotspot", "Hotspot, Off", "Hotspot, On",
    )

    const val SYSTEM_UI = "com.android.systemui"

    private val SPOKEN = setOf("yes", "no", "confirm", "cancel", "continue")
    private val DECLINING = setOf("no", "cancel")
    private val PACKAGE = Regex("[A-Za-z0-9_.]{1,128}")

    private fun word(text: String?): String = text?.trim()?.replace(Regex("\\s+"), "_")?.ifEmpty { null } ?: "none"
}
