package dev.kestrel.commands.text

/**
 * A phonetic key for names and labels (research r23). It respells Serbian letters before it folds them, so that "Đorđe"
 * and "Georgie" share the key "jrj", joins the words, keeps a leading vowel, drops the other vowels, collapses repeated
 * letters and never truncates. Voiced and unvoiced pairs stay apart, so that "Save" and "Safe" differ.
 */
object PhoneticKey {
    fun of(text: String): String {
        val letters = spelled(text).filter { it.isLetterOrDigit() }
        val key = StringBuilder()
        for ((index, char) in letters.withIndex()) {
            if (index > 0 && char in VOWELS) continue
            if (key.isNotEmpty() && key.last() == char) continue
            key.append(char)
        }
        return key.toString()
    }

    /** [text] respelled as the key reads it, before the vowels go: "Giorgie" and "Đorđe" become "jiorjie" and "jorje". */
    internal fun spelled(text: String): String = Normalizer.fold(respell(Normalizer.transliterate(text)))

    private fun respell(text: String): String {
        val spelled = text.replace("č", "ch").replace("ć", "ch").replace("š", "sh").replace("ž", "zh").replace("đ", "dj")
            .replace("dj", "j")
        return SOFT_G.replace(spelled, "j")
    }

    private val SOFT_G = Regex("g(?=[ei])")
    private const val VOWELS = "aeiouy"
}
