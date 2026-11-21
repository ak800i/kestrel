package dev.kestrel.safety

import java.net.URI
import java.net.URISyntaxException

/** What [LinkCheck] says about a link: it may open with at least [Allowed.category], or it is refused with a sentence. */
sealed interface LinkVerdict {
    data class Allowed(val category: Category) : LinkVerdict

    data class Refused(val reason: String) : LinkVerdict {
        override fun toString(): String = "Refused(reasonLength=${reason.length})"
    }
}

/**
 * Checks every link before Kestrel opens it (design D11 and D32 item 12 of sub-project 5): `open_url` opens only `https`,
 * `open_deep_link` only spec 11.4's schemes, never `intent:`, `file:`, `content:`, `javascript:` or `android-app:`, and
 * never a link whose activity belongs to Kestrel, an agent-free app or Play services. A `tel:` link opens only the dial
 * screen, and only for a number that Kestrel may dial ([EmergencyGuard.telAllowed]). A link that came from the screen or
 * a data tool and carries a query or a fragment with data ("=", "&" or a digit; a section anchor is none) sends that data
 * off the phone, so it is `SENDING` (research r52, fixture 12; review 2 M5 and 2-2 New 5). Data in a link's path is not recognised; spec 12.1 names that gap.
 */
object LinkCheck {
    val DEEP_LINK_SCHEMES: Set<String> = setOf("viber", "whatsapp", "tg", "waze", "google.navigation", "geo", "tel", "sms", "mailto")
    private val NEVER = setOf("intent", "file", "content", "javascript", "android-app")
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    private const val PLAY_SERVICES = "com.google.android.gms"
    private const val KESTREL = "dev.kestrel"

    fun check(
        link: String,
        kind: ActionKind,
        fromOwner: Boolean,
        targetPackage: String?,
        isAgentFree: (String?) -> Boolean,
        emergency: EmergencyNumbers,
    ): LinkVerdict {
        val text = link.trim()
        val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase() ?: return LinkVerdict.Refused("The link has no scheme.")
        if (scheme in NEVER) return LinkVerdict.Refused("Kestrel never opens $scheme links.")
        when (kind) {
            ActionKind.OPEN_URL -> if (scheme != "https") return LinkVerdict.Refused("Only https links.")
            ActionKind.OPEN_DEEP_LINK -> if (scheme !in DEEP_LINK_SCHEMES) return LinkVerdict.Refused("Kestrel opens no $scheme links.")
            else -> return LinkVerdict.Refused("Only open_url and open_deep_link open links.")
        }
        if (scheme == "https") {
            val host = try {
                URI(text).host
            } catch (e: URISyntaxException) {
                null
            }
            if (host.isNullOrEmpty()) return LinkVerdict.Refused("The link cannot be read.")
        }
        if (targetPackage == KESTREL || targetPackage == PLAY_SERVICES || (targetPackage != null && isAgentFree(targetPackage))) {
            return LinkVerdict.Refused("The link opens an app that the agent may not use.")
        }
        if (scheme == "tel") {
            val number = text.substringAfter(':').substringBefore('?')
            if (!EmergencyGuard.telAllowed(number, emergency)) return LinkVerdict.Refused("Kestrel doesn't open this number; dial it yourself.")
        }
        // Data in the query or the fragment of a link that the screen or a data tool gave leaves the phone with it. A
        // fragment counts only when it holds "=", "&" or a digit, because a section anchor ("#History") is a word.
        val fragment = text.substringAfter('#', "")
        val data = text.substringAfter('?', "").substringBefore('#').isNotEmpty() || fragment.any { it == '=' || it == '&' || it.isDigit() }
        val category = if (!fromOwner && data) Category.SENDING else Category.READING
        return LinkVerdict.Allowed(category)
    }
}
