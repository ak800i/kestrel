package dev.kestrel.safety

import kotlinx.serialization.Serializable

/** The safety modes of spec 12.3 (design D13 of sub-project 5); the floor holds in every one of them. */
@Serializable
enum class Mode { STANDARD, RELAXED, FULL_AUTO }

/**
 * Safety settings. Test mode is on until the owner turns it off for daily use (design spec, D25 and section 12.7). The
 * owner's e-mail addresses and names are test mode's recipients (design D18 of sub-project 5), so `toString` prints only
 * how many there are and whether they are set.
 */
@Serializable
data class SafetySettings(
    val testMode: Boolean = true,
    val mode: Mode = Mode.STANDARD,
    val ownerEmails: List<String> = emptyList(),
    val viberSelfChatName: String = "My Notes",
    val whatsappOwnName: String? = null,
) {
    override fun toString(): String =
        "SafetySettings(testMode=$testMode, mode=$mode, ownerEmails=${ownerEmails.size}, viberSelfChatName=${viberSelfChatName.length} chars, " +
            "whatsappOwnName=${if (whatsappOwnName == null) "none" else "set"})"
}
