package dev.kestrel.safety

import dev.kestrel.commands.text.Normalizer

/**
 * A text that a task typed, with the field that it went into: [text] is what was typed, a vault's value included, so it
 * stays in memory only and its text form prints lengths (design D14 and D17 of sub-project 5).
 */
data class TypedText(val fieldKey: String, val fieldName: String?, val packageName: String?, val text: String) {
    override fun toString(): String = "TypedText(fieldNameLength=${fieldName?.length ?: 0}, textLength=${text.length})"
}

/**
 * A share flow (design D13 of sub-project 5): from `share_media`, `compose_sms`, an app's Share or Forward button or a
 * system share sheet, until the app shows a chat titled as one of the [selected] rows, the task opens something else or
 * goes home, or it leaves the flow's apps. [selected] holds the name of every row that the agent tapped in the list of
 * rows, so that it is never smaller than the real selection. [serial] numbers the task's flows from 1, so that a second
 * share of other items to the same chat is another flow, which a yes for the first one never covers (review 4 C2).
 */
data class ShareFlow(val receivingPackage: String, val selected: List<String>, val count: ShareCount?, val serial: Int = 0) {
    override fun toString(): String = "ShareFlow(serial=$serial, selected=${selected.size}, count=${count != null})"
}

/** A protected trail in Settings, from the page whose heading [startTitle] started it (design D13 of sub-project 5). */
data class ProtectedTrail(val startTitle: String) {
    override fun toString(): String = "ProtectedTrail(titleLength=${startTitle.length})"
}

/**
 * The task's own state that the safety check reads (design D13 of sub-project 5): its allowance, the folded words of the
 * owner's request and answers, the memory values that it used, what it typed, its share flow and its protected trail,
 * how many share flows it started, and the vault's values that it revealed ([secrets], each with the words for its kind,
 * such as "your one-time code"), which a question names by those words, never by the value (design D14; review 2 I3).
 */
data class TaskSafety(
    val allowance: TaskAllowance,
    val ownerWords: Set<String> = emptySet(),
    val memoryValues: List<String> = emptyList(),
    val typed: List<TypedText> = emptyList(),
    val shareFlow: ShareFlow? = null,
    val trail: ProtectedTrail? = null,
    val flows: Int = 0,
    val secrets: Map<String, String> = emptyMap(),
) {
    /** Adds the words of an answer of the owner's, so that typing them is typing the request. */
    fun withOwnerWords(text: String): TaskSafety = copy(ownerWords = ownerWords + Normalizer.words(text))

    /**
     * Remembers a vault's [value] that the task typed after the owner's yes, with the [words] that name its kind
     * (`Placeholders.forConfirmation` of its placeholder), so that a later question shows and speaks the words instead.
     */
    fun withSecret(value: String, words: String): TaskSafety = copy(secrets = secrets + (value to words))

    override fun toString(): String =
        "TaskSafety(allowance=$allowance, ownerWords=${ownerWords.size}, memoryValues=${memoryValues.size}, typed=${typed.size}, " +
            "shareFlow=$shareFlow, trail=$trail, flows=$flows, secrets=${secrets.size})"

    companion object {
        /** The state at a task's start, from the owner's [request] and the [knownNames] of memory and the contacts. */
        fun start(request: String, knownNames: Set<String> = emptySet()): TaskSafety =
            TaskSafety(TaskAllowance.from(request, knownNames), ownerWords = Normalizer.words(request).toSet())
    }
}

/**
 * What the safety check takes from Android and the owner's settings besides the snapshot (design D13 and D16): whether a
 * package is agent-free, Android's emergency-number check, the phone apps (the default phone app added by `:app`), and
 * the debug-only aliases that map a fixture's package to a real app's self-chat rule (design D18).
 */
data class SafetyEnvironment(
    val isAgentFree: (String?) -> Boolean = { it == "dev.kestrel" },
    val emergency: EmergencyNumbers = EmergencyNumbers.UNKNOWN,
    val phonePackages: Set<String> = EmergencyGuard.PHONE_PACKAGES,
    val selfChatAliases: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "SafetyEnvironment(phonePackages=${phonePackages.size}, aliases=${selfChatAliases.size})"
}
