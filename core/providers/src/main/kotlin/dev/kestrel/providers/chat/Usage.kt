package dev.kestrel.providers.chat

/**
 * Token counts, normalised across the styles (design D6): [input] is the input that was neither read from nor written to
 * a cache, [output] includes reasoning, and [reasoning] is the part of [output] that was reasoning (shown, never priced on
 * its own). [estimated] is true when the provider reported nothing and Kestrel counted.
 */
data class Usage(
    val input: Long,
    val output: Long,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val reasoning: Long = 0,
    val estimated: Boolean = false,
) {
    /** The whole input, which decides a long-context price tier (design D14). */
    val totalInput: Long get() = input + cacheRead + cacheWrite
}
