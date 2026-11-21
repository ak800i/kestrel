package dev.kestrel.safety

import dev.kestrel.commands.text.Normalizer

/**
 * What may stand between the digits or letters of a secret's groups: Android writes them with a no-break, figure, narrow
 * no-break or thin space too (review 5A-4 Minor 2). The redactor and the questions' hiding of vault values share them.
 */
internal object Gaps {
    const val SPACE = "[ \u00A0\u2007\u202F\u2009]"
    const val GAP = "[ \u00A0\u2007\u202F\u2009-]"
    val CHARS = setOf(' ', '\u00A0', '\u2007', '\u202F', '\u2009', '-')
}

/**
 * Replaces secrets with typed placeholders in every text that goes into a request (design D17 of sub-project 5; spec
 * 12.6): one-time codes near a keyword (in the text or in a neighbouring text of the same window), card numbers that pass
 * the Luhn check, IBANs with a valid checksum, JMBG numbers and access tokens. Phone numbers and e-mail addresses stay.
 * Each text is redacted exactly once, when it enters the conversation, before it is quoted; a value that the [vault]
 * already holds is replaced wherever it appears later, with or without a keyword. A literal placeholder in untrusted
 * text becomes `(KIND_n)` first, so that only Kestrel's own placeholders reach the vault.
 */
class Redactor(val vault: Vault) {
    private class Hit(val range: IntRange, val kind: SecretKind)

    fun redact(text: String, neighbours: List<String> = emptyList()): String {
        var result = Placeholders.PATTERN.replace(text) { "(${it.value.substring(1, it.value.length - 1)})" }
        result = replaceKnown(result)
        for (finder in listOf(::tokens, ::ibans, ::jmbgs, ::cards)) result = replace(result, finder(result))
        return replace(result, codes(result, neighbours))
    }

    private fun replace(text: String, hits: List<Hit>): String {
        if (hits.isEmpty()) return text
        // Of overlapping hits, the one that starts first wins, and of those the longest, so that a JWT stays one token.
        val kept = mutableListOf<Hit>()
        for (hit in hits.sortedWith(compareBy<Hit> { it.range.first }.thenByDescending { it.range.last })) {
            if (kept.none { it.range.last >= hit.range.first }) kept += hit
        }
        // Right to left, so that the earlier ranges stay where they are; the placeholders are numbered left to right.
        val placeholders = kept.map { vault.placeholderFor(it.kind, text.substring(it.range)) }
        val builder = StringBuilder(text)
        for (index in kept.indices.reversed()) builder.replace(kept[index].range.first, kept[index].range.last + 1, placeholders[index])
        return builder.toString()
    }

    /** Every value that the vault holds, where its characters appear again, with or without separators between them. */
    private fun replaceKnown(text: String): String {
        var result = text
        for ((kind, value) in vault.known()) {
            val pattern = when (kind) {
                SecretKind.OTP, SecretKind.CARD, SecretKind.JMBG ->
                    Regex("(?<![0-9])" + Vault.canonical(kind, value).toCharArray().joinToString("$GAP?") + "(?![0-9])")
                SecretKind.IBAN -> Regex(Vault.canonical(kind, value).toCharArray().joinToString("$SPACE?") { Regex.escape(it.toString()) }, RegexOption.IGNORE_CASE)
                SecretKind.TOKEN -> Regex(Regex.escape(value))
            }
            result = pattern.replace(result) { vault.placeholderFor(kind, it.value) }
        }
        return result
    }

    private fun tokens(text: String): List<Hit> {
        val hits = TOKEN_PATTERNS.flatMap { pattern -> pattern.findAll(text).map { Hit(it.range, SecretKind.TOKEN) } }
        val generic = LONG_RUN.findAll(text).filter { match ->
            val run = match.value
            val mixed = run.any(Char::isUpperCase) && run.any(Char::isLowerCase) && run.any(Char::isDigit)
            val slug = '-' in run && run.split('-').all { part -> part.all { it.isLowerCase() || it.isDigit() } }
            (mixed || run.count(Char::isDigit) >= 8) && !slug
        }.map { Hit(it.range, SecretKind.TOKEN) }
        return hits + generic
    }

    /**
     * IBANs: a run that is written without spaces or in groups of four (the last one shorter). A country of the table
     * takes its own length from the run's start, which must end where a group ends; any other country takes the longest
     * run of whole groups that passes mod 97, so that a currency code or a year after it does not hide it (review 5A-34
     * Minor 2). A run in small letters counts only for a country of the table (Minor 3). A run that is no IBAN leaves the
     * search at its next character, so that it never hides an IBAN that starts inside it.
     */
    private fun ibans(text: String): List<Hit> {
        val hits = mutableListOf<Hit>()
        var from = 0
        while (true) {
            val match = IBAN.find(text, from) ?: return hits
            val hit = iban(text, match)
            if (hit != null) hits += hit
            from = (hit?.range?.last ?: match.range.first) + 1
        }
    }

    private fun iban(text: String, match: MatchResult): Hit? {
        val positions = match.value.indices.filter { match.value[it].isLetterOrDigit() }.map { match.range.first + it }
        val compact = positions.joinToString("") { text[it].uppercaseChar().toString() }
        val length = Checksums.IBAN_LENGTHS[compact.substring(0, 2)]
        // The lengths at which a group ends, longest first; outside the table, only those without small letters.
        val ends = positions.indices.filter { it == positions.lastIndex || positions[it + 1] != positions[it] + 1 }.map { it + 1 }.reversed()
        val tries = if (length != null) listOf(length).filter { it in ends } else ends.filter { end -> positions.take(end).none { text[it].isLowerCase() } }
        val found = tries.firstOrNull { Checksums.iban(compact.substring(0, it)) }
        return found?.let { Hit(match.range.first..positions[it - 1], SecretKind.IBAN) }
    }

    private fun jmbgs(text: String): List<Hit> =
        THIRTEEN.findAll(text).filter { Checksums.jmbg(it.value) }.map { Hit(it.range, SecretKind.JMBG) }.toList()

    /**
     * Card numbers: at each start of a run of digits, every length from 13 to 19 that passes the Luhn check (review B M7);
     * after a hit, the search goes on after it, so that a second card in the same run is found too (review 2 M3).
     */
    private fun cards(text: String): List<Hit> = DIGIT_RUN.findAll(text).flatMap { match ->
        val positions = match.value.indices.filter { match.value[it].isDigit() }.map { match.range.first + it }
        val digits = positions.joinToString("") { text[it].toString() }
        // A group ends after a digit that a separator follows.
        val ends = positions.indices.filter { it == positions.lastIndex || positions[it + 1] != positions[it] + 1 }.map { it + 1 }.toSet()
        val starts = setOf(0) + ends
        val found = mutableListOf<Hit>()
        var start = 0
        while (start < positions.size) {
            val length = (19 downTo 13).firstOrNull { length ->
                val end = start + length
                end <= digits.length && ((start in starts && end in ends) || ends.size == 1) && Checksums.luhn(digits.substring(start, end))
            }
            if (length == null) {
                start++
            } else {
                found += Hit(positions[start]..positions[start + length - 1], SecretKind.CARD)
                start += length
            }
        }
        found.asSequence()
    }.toList()

    /**
     * One-time codes: 4 to 8 digits, also in up to three groups split by a space (also a no-break, narrow or thin one) or
     * a dash, within 40 characters of a keyword or beside one.
     */
    private fun codes(text: String, neighbours: List<String>): List<Hit> {
        val candidates = CODE.findAll(text).filter { it.value.count(Char::isDigit) in 4..8 && !inNumber(text, it.range) }.toList()
        if (candidates.isEmpty()) return emptyList()
        if (neighbours.any { keywordRanges(it).isNotEmpty() }) return candidates.map { Hit(it.range, SecretKind.OTP) }
        val keywords = keywordRanges(text)
        return candidates.filter { candidate ->
            keywords.any { keyword ->
                val gap = if (keyword.last < candidate.range.first) candidate.range.first - keyword.last else keyword.first - candidate.range.last
                gap <= NEAR
            }
        }.map { Hit(it.range, SecretKind.OTP) }
    }

    /**
     * Whether the digits at [range] are part of a longer number, such as a phone number: a "+" before them, or another
     * digit across one separator on either side (review 2 M4).
     */
    private fun inNumber(text: String, range: IntRange): Boolean {
        fun at(index: Int) = text.getOrNull(index)
        val before = at(range.first - 1)
        if (before == '+') return true
        if (before in GAPS && (at(range.first - 2)?.isDigit() == true || at(range.first - 2) == '+')) return true
        val after = at(range.last + 1)
        return after in GAPS && at(range.last + 2)?.isDigit() == true
    }

    /** Where the code keywords stand in [text], found on the folded text and mapped back to [text]. */
    private fun keywordRanges(text: String): List<IntRange> {
        val folded = Normalizer.withOffsets(text)
        val words = folded.words.map { folded.text.substring(it) }
        return words.indices.filter { words[it] in KEYWORDS }.map { folded.span(it, it + 1) }
    }

    private companion object {
        const val NEAR = 40

        const val SPACE = Gaps.SPACE
        const val GAP = Gaps.GAP
        val GAPS = Gaps.CHARS
        val KEYWORDS = setOf("code", "codes", "kod", "kodovi", "sifra", "pin", "otp", "verification", "verifikacioni", "lozinka", "password")
        // Up to three groups ("12 34 56"); a longer number, such as a phone number, has more than 8 digits (review 2-2 New 3).
        val CODE = Regex("(?<![0-9A-Za-z])[0-9]{1,8}(?:$GAP[0-9]{1,8}){0,2}(?![0-9A-Za-z])")
        val DIGIT_RUN = Regex("[0-9](?:$GAP?[0-9]){12,}")
        val THIRTEEN = Regex("(?<![0-9])[0-9]{13}(?![0-9])")
        val IBAN = Regex("(?<![A-Za-z0-9])[A-Za-z]{2}[0-9]{2}(?:[A-Za-z0-9]{11,30}|(?:$SPACE[A-Za-z0-9]{4}){2,8}(?:$SPACE[A-Za-z0-9]{1,3})?)(?![A-Za-z0-9])")
        val LONG_RUN = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9_-])")
        val TOKEN_PATTERNS = listOf(
            Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
            Regex("(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{16,}"),
            Regex("(?<![A-Za-z0-9])sk_live_[A-Za-z0-9]{16,}"),
            Regex("(?<![A-Za-z0-9])gh[po]_[A-Za-z0-9]{20,}"),
            Regex("(?<![A-Za-z0-9])github_pat_[A-Za-z0-9_]{20,}"),
            Regex("(?<![A-Za-z0-9])xox[abprs]-[A-Za-z0-9-]{10,}"),
            Regex("(?<![A-Za-z0-9])AKIA[A-Z0-9]{16}(?![A-Za-z0-9])"),
            Regex("(?<![A-Za-z0-9])AIza[A-Za-z0-9_-]{35}(?![A-Za-z0-9_-])"),
        )
    }
}
