package dev.kestrel.providers.credentials

/** The rule for a pasted API key (design D9). */
object ApiKeys {
    /**
     * Whether [key], trimmed, can be sent as a header: not empty, and only printable ASCII without spaces (0x21 to 0x7e).
     * OkHttp refuses other characters with an exception whose message holds the value.
     */
    fun acceptable(key: String): Boolean {
        val trimmed = key.trim()
        return trimmed.isNotEmpty() && trimmed.all { it in '!'..'~' }
    }
}
