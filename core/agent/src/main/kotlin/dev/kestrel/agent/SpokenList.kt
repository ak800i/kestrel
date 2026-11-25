package dev.kestrel.agent

/** Parts as an English sentence lists them: "a", "a and b", "a, b and c". */
internal object SpokenList {
    fun of(parts: List<String>): String = if (parts.size <= 1) parts.joinToString("") else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
}
