package dev.kestrel.safety

import dev.kestrel.commands.text.Normalizer
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

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
 * off the phone, so it is `SENDING` (research r52, fixture 12; review 2 M5 and 2-2 New 5), unless it is a `geo:` or an https
 * search whose query values are only [check]'s `ownerWords` (fix-6b item 10, review 6b-links I1 and M1). Data in a link's
 * path is not recognised; spec 12.1 names that gap.
 */
object LinkCheck {
    val DEEP_LINK_SCHEMES: Set<String> = setOf("viber", "whatsapp", "tg", "waze", "google.navigation", "geo", "tel", "sms", "mailto")
    private val NEVER = setOf("intent", "file", "content", "javascript", "android-app")
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    private const val PLAY_SERVICES = "com.google.android.gms"
    private const val KESTREL = "dev.kestrel"

    /** The hosts whose links can send a message by themselves, with every host below them. */
    private val MESSENGER_HOSTS = setOf(
        "t.me", "telegram.me", "telegram.dog", "telegram.org", "wa.me", "whatsapp.com", "whatsapp.net", "viber.com", "viber.me", "vb.me",
    )

    /** The parameter names of a search, by scheme (spec 12.1): a link with any other parameter name keeps its data rule. */
    val SEARCH_KEYS: Map<String, Set<String>> = mapOf(
        "geo" to setOf("q"),
        "https" to setOf("q", "query", "search_query", "search", "text"),
    )

    /** The messengers' apps: a link that one of them opens can send by itself, whatever its host. */
    private val MESSENGER_PACKAGES = setOf(SelfChats.TELEGRAM, SelfChats.TELEGRAM_WEB, SelfChats.WHATSAPP, "com.whatsapp.w4b", SelfChats.VIBER)

    fun check(
        link: String,
        kind: ActionKind,
        fromOwner: Boolean,
        targetPackage: String?,
        isAgentFree: (String?) -> Boolean,
        emergency: EmergencyNumbers,
        ownerWords: Set<String> = emptySet(),
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
            if (hostOf(text).isNullOrEmpty()) return LinkVerdict.Refused("The link cannot be read.")
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
        val data = text.substringAfter('?', "").substringBefore('#').isNotEmpty() || fragmentHoldsData(text)
        val category = if (!fromOwner && data && !searchesOwnerWords(text, scheme, targetPackage, ownerWords)) Category.SENDING else Category.READING
        return LinkVerdict.Allowed(category)
    }

    /**
     * A `geo:` or https link that no messenger opens, whose every parameter is one of [SEARCH_KEYS] and whose every value,
     * decoded, holds only letters that the fold keeps, ASCII digits and spaces, and folds to [ownerWords] alone (the owner's
     * words and the memory values that the task used): it types his request into a search, so it reads. A messenger's host
     * (letter case and a trailing dot aside) or app, another name, a parameter without a value, any other character (a sign,
     * a mark, a superscript or a letter outside the Basic Multilingual Plane, which the fold would drop), a fragment with
     * data, a placeholder or a value that cannot be decoded keeps the link's data rule.
     */
    private fun searchesOwnerWords(text: String, scheme: String, targetPackage: String?, ownerWords: Set<String>): Boolean {
        val keys = SEARCH_KEYS[scheme] ?: return false
        if (targetPackage in MESSENGER_PACKAGES) return false
        if (scheme == "https") {
            val host = hostOf(text)?.lowercase()?.trimEnd('.') ?: return false
            if (MESSENGER_HOSTS.any { host == it || host.endsWith(".$it") }) return false
        }
        if (fragmentHoldsData(text)) return false
        val query = text.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return false
        return query.split('&').all { parameter ->
            val name = parameter.substringBefore('=', "")
            val value = decode(parameter.substringAfter('=', ""), plusIsSpace = true)
            val words = value?.let(Normalizer::words).orEmpty()
            name in keys && value != null && searchable(value) && !Placeholders.holdsOne(value) && words.isNotEmpty() && words.all { it in ownerWords }
        }
    }

    // Only characters that the fold keeps as they are or as plain letters: the words that it compares are then all that
    // the value holds, apart from letter case, diacritics and the script of a letter.
    private fun searchable(value: String): Boolean = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC).all {
        it == ' ' || it in '0'..'9' || (it.isLetter() && Normalizer.normalize(it.toString()).isNotEmpty())
    }

    private fun fragmentHoldsData(text: String): Boolean = text.substringAfter('#', "").any { it == '=' || it == '&' || it.isDigit() }

    private fun hostOf(text: String): String? = try {
        URI(text).host
    } catch (e: URISyntaxException) {
        null
    }

    /**
     * [text] with its percent escapes decoded as UTF-8, and "+" as a space when [plusIsSpace] (a web form's query); null when
     * an escape or its bytes cannot be read.
     */
    fun decode(text: String, plusIsSpace: Boolean): String? {
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '%') {
                val hex = text.substring(index + 1, minOf(index + 3, text.length))
                if (hex.length != 2 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                bytes.write(hex.toInt(16))
                index += 3
                continue
            }
            val point = text.codePointAt(index)
            val piece = if (char == '+' && plusIsSpace) " " else String(Character.toChars(point))
            bytes.write(piece.toByteArray(Charsets.UTF_8))
            index += Character.charCount(point)
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}
