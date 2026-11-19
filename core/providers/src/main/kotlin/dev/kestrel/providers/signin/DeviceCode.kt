package dev.kestrel.providers.signin

/**
 * What the sign-in page shows while a device code waits (design D9): the code in large type, the address that the owner
 * opens, the time when the code expires and the poll interval. The code lets anyone approve the sign-in, so toString
 * prints only its length.
 */
data class DeviceCodePrompt(val userCode: String, val verificationUrl: String, val expiresAtMillis: Long, val intervalMillis: Long) {
    override fun toString(): String =
        "DeviceCodePrompt(userCodeLength=${userCode.length}, expiresAtMillis=$expiresAtMillis, intervalMillis=$intervalMillis)"
}

/**
 * A sign-in with a device code (design D9, RFC 8628): [start] asks for a code, and [await] polls until the owner approves
 * or refuses, or the code expires. A flow is used once. [signInHosts] are the hosts that the sign-in needs while it runs;
 * the caller leases them on the allowlist and closes the lease when the flow ends.
 */
interface DeviceCodeFlow {
    val signInHosts: Set<String>

    suspend fun start(): DeviceCodePrompt

    suspend fun await(prompt: DeviceCodePrompt): SignInResult
}
