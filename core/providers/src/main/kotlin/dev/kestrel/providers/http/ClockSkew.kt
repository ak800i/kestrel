package dev.kestrel.providers.http

import java.util.concurrent.atomic.AtomicLong

/**
 * How far the phone's clock is from the providers' clocks (design D9), for the credentials whose signature carries the time:
 * AWS's SigV4 (BedrockAdapter and BedrockModels) and Vertex's JWT (ServiceAccountAuth). The skew belongs to the phone,
 * not to a provider, so the process keeps one holder, [SHARED], which those classes take by default; tests give their
 * own. A corrected attempt that succeeded stores its offset with [learn], and every later request is signed with
 * [offsetMillis] at once. A refusal of a request signed with the stored offset is corrected the same way (the reply's
 * `Date` compared with the clock that signed), so that a clock that the phone fixes later is learnt again. Only the
 * number is kept; toString prints nothing of it.
 */
class ClockSkew {
    private val offset = AtomicLong(0)

    /** The server's time minus the phone's, in milliseconds, that the next request is signed with; 0 until one is learnt. */
    val offsetMillis: Long get() = offset.get()

    /** Keeps the offset of a corrected attempt that the server accepted. */
    fun learn(offsetMillis: Long) {
        offset.set(offsetMillis)
    }

    override fun toString(): String = "ClockSkew"

    companion object {
        /** The phone's offset for the whole process. */
        val SHARED = ClockSkew()
    }
}
