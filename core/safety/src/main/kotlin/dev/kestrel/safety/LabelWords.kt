package dev.kestrel.safety

import dev.kestrel.commands.text.Normalizer

/**
 * The words in a label that suggest a category (design D13 of sub-project 5; spec 12.2), in English and Serbian, folded
 * by [Normalizer], which removes diacritics and writes Cyrillic in Latin letters, so "пошаљи" and "pošalji" are both
 * "posalji". A word or phrase counts only whole, as consecutive words of the label.
 */
object LabelWords {
    private val WORDS: Map<Category, List<String>> = mapOf(
        Category.SENDING to listOf(
            "send", "post", "share", "confirm", "submit", "reply", "forward", "record", "voice message",
            "posalji", "posaljite", "objavi", "podeli", "podijeli", "podelite", "potvrdi", "potvrdite", "odgovori", "odgovorite",
            "prosledi", "prosledite", "snimi",
        ),
        Category.CALLS to listOf("call", "dial", "video call", "pozovi", "pozovite", "nazovi"),
        Category.DELETING to listOf(
            "delete", "remove", "erase", "clear", "discard", "trash", "bin",
            "obrisi", "obrisite", "izbrisi", "izbrisite", "ukloni", "uklonite",
        ),
        Category.PAYMENTS to listOf(
            "pay", "buy", "order", "checkout", "place order", "subscribe", "purchase",
            "plati", "platite", "kupi", "kupite", "naruci", "narucite", "pretplati",
        ),
        Category.PROTECTED to listOf(
            "install", "allow", "grant", "permit", "uninstall", "activate this device admin app",
            "instaliraj", "instalirajte", "dozvoli", "dozvolite",
        ),
    )

    /** A tap on these is checked against the typed texts before it runs (design D14): a send's words and these. */
    val SAVE_WORDS: Set<String> = setOf("save", "done", "apply", "ok", "sacuvaj", "gotovo", "primeni")

    /** A tap on these starts a share flow (design D13). */
    val SHARE_START_WORDS: Set<String> = setOf("share", "forward", "podeli", "podijeli", "prosledi")

    /** The most restrictive category whose words occur in [text], or null. */
    fun categoryOf(text: String?): Category? {
        val words = Normalizer.words(text ?: return null)
        if (words.isEmpty()) return null
        return WORDS.entries.filter { (_, list) -> list.any { holds(words, it) } }.maxOfOrNull { it.key }
    }

    /** Whether [text] holds a word of [category], also when the words of a higher category occur too. */
    fun holds(text: String?, category: Category): Boolean {
        val words = Normalizer.words(text ?: return false)
        return WORDS[category].orEmpty().any { holds(words, it) }
    }

    /** Whether [text] holds one of [words] (folded words or phrases) as whole words. */
    fun holdsWord(text: String?, words: Set<String>): Boolean {
        val folded = Normalizer.words(text ?: return false)
        return words.any { holds(folded, it) }
    }

    private fun holds(words: List<String>, phrase: String): Boolean {
        val parts = phrase.split(' ')
        if (parts.size > words.size) return false
        return (0..words.size - parts.size).any { start -> parts.indices.all { words[start + it] == parts[it] } }
    }
}
