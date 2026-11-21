package dev.kestrel.safety

import dev.kestrel.commands.phone.PhoneNumbers
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot

/** Android's emergency-number check, which `:app` passes in: null when Android cannot tell (design D13 of sub-project 5). */
fun interface EmergencyNumbers {
    fun isEmergency(digits: String): Boolean?

    companion object {
        /** Cannot tell, which counts as an emergency number wherever it matters. */
        val UNKNOWN = EmergencyNumbers { null }
    }
}

/**
 * The pure parts of plan 3C's emergency guard (design spec, section 8.7), moved from `:app`'s `CallCommands.kt` so that
 * the agent's safety check uses the same rules (design D13 of sub-project 5): R1's words, R2's phone packages and the
 * rules of a phone app's number field. `:app`'s guard calls these.
 */
object EmergencyGuard {
    /** The phone apps and Android's call services; `:app` adds the default phone app. */
    val PHONE_PACKAGES: Set<String> = setOf(
        "com.android.phone", "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
        "com.samsung.android.incallui", "com.android.server.telecom",
        // One UI's emergency dialer.
        "com.samsung.android.app.telephonyui",
    )

    /** The contacts apps, where a tap on a number row places a call (design D13 of sub-project 5). */
    val CONTACTS_PACKAGES: Set<String> = setOf("com.android.contacts", "com.google.android.contacts", "com.samsung.android.app.contacts")

    private val EMERGENCY_WORDS = setOf("emergency", "sos")
    private val DELETE_IDS = setOf("deletebutton", "delete_button", "backspace")
    private val DELETE_NAMES = setOf("delete", "backspace", "erase")
    private const val DIGITS = ":id/digits"
    private const val KESTREL = "dev.kestrel"

    /** R1: whether the name of element [index] holds "emergency" or "sos"; never Kestrel's own windows, such as its bar. */
    fun emergencyNamed(snapshot: ScreenSnapshot, index: Int): Boolean {
        val element = snapshot.elements[index]
        if (element.packageName == KESTREL) return false
        // A view ID such as "emergencyButton" or "sos_call" splits into words.
        val id = element.viewId?.substringAfter(":id/")?.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        val names = listOfNotNull(element.text, element.contentDescription, id, element.label.takeIf { element.isActionable })
        return names.any(::namesEmergency)
    }

    /** R1's words in [text], such as an OCR line under a tap at a point: "emergency" or "sos" as a whole word. */
    fun namesEmergency(text: String?): Boolean = text != null && Normalizer.words(text).any { it in EMERGENCY_WORDS }

    /**
     * Whether [element] is a phone app's number field: a text field, or a field with the view ID `digits`, which
     * Android's emergency dialer does not make editable.
     */
    fun isNumberField(element: ScreenElement): Boolean =
        element.editable && !element.password || element.viewId?.endsWith(DIGITS) == true

    /**
     * Whether the number field [element] shows [text] as a number: the field with the view ID `digits` with any text,
     * and another text field only with a digit, `*` or `#`, so that a name in the phone app's search box counts as none.
     */
    fun showsNumber(element: ScreenElement, text: String?): Boolean {
        if (text.isNullOrBlank() || !isNumberField(element)) return false
        return element.viewId?.endsWith(DIGITS) == true || text.any { it.isDigit() || it == '*' || it == '#' }
    }

    /**
     * Whether [element] is a phone app's delete key: by its view ID, or, when it takes a touch, by a whole name such as
     * "Delete" or "Backspace".
     */
    fun isDeleteKey(element: ScreenElement): Boolean {
        if (element.viewId?.substringAfter(":id/")?.lowercase() in DELETE_IDS) return true
        if (!element.clickable && !element.longClickable) return false
        return listOfNotNull(element.text, element.contentDescription).any { Normalizer.normalize(it) in DELETE_NAMES }
    }

    /**
     * Whether [element]'s own text or content description, trimmed, is as a whole a number that a phone app dials and
     * that Android reports as an emergency number, or cannot check ([emergency] not false).
     */
    fun showsEmergencyNumber(element: ScreenElement, emergency: (String) -> Boolean?): Boolean =
        listOfNotNull(element.text, element.contentDescription).any { text ->
            val digits = PhoneNumbers.dialable(text.trim()) ?: return@any false
            emergency(PhoneNumbers.mainNumber(digits)) != false
        }

    /**
     * Whether Kestrel may call [digits] itself (design D11 of sub-project 3): a long number without "*" or "#" that
     * Android reports as no emergency number; when Kestrel cannot tell ([emergency] null), it may not.
     */
    fun mayCall(digits: String, emergency: Boolean?): Boolean = PhoneNumbers.mayAutoDial(digits) && emergency == false

    /**
     * Whether a `tel:` link may open the dial screen with [number] (design D11 and D32 item 12 of sub-project 5): never
     * an emergency number, one that Android cannot check, one with fewer than 5 digits, or one with `*` or `#`.
     */
    fun telAllowed(number: String, emergency: EmergencyNumbers): Boolean {
        val digits = PhoneNumbers.dialable(number) ?: return false
        if (digits.any { it == '*' || it == '#' }) return false
        return mayCall(digits, emergency.isEmergency(PhoneNumbers.mainNumber(digits)))
    }
}
