package dev.kestrel.eval.hook

import dev.kestrel.eval.device.Refusal

/**
 * What the debug hook provider-standin answers about Kestrel's providers: whether the Copilot stand-in runs, the default
 * profile's kind (none, copilot or other), the number of Copilot profiles, the default profile's ID, which Kestrel makes
 * at random and which holds nothing secret (none without a default profile), and whether its credential is stored
 * (present, missing, or none for a profile without one). scripts\lib\DeviceGuard.ps1 reads the same answer
 * (ConvertFrom-StandInAnswer) and compares the same fields (Get-OwnerDataChange).
 */
data class ProviderState(val standIn: Boolean, val profile: String, val copilot: Int, val id: String, val credential: String) {
    companion object {
        private val CREDENTIAL_STATES = setOf("present", "missing", "none")

        /** Reads the hook's answer; an answer without one of the fields, as an older build's, is refused. */
        fun from(answer: HookAnswer): ProviderState {
            val standIn = answer["standin"]
            val profile = answer["profile"]
            val copilot = answer["copilot"]?.toIntOrNull()
            val id = answer["id"]?.takeIf { it.isNotEmpty() }
            val credential = answer["credential"]?.takeIf { it in CREDENTIAL_STATES }
            if (standIn == null || profile == null || copilot == null || id == null || credential == null) {
                throw Refusal("Not an answer of provider-standin: '${answer.text}'. Install the current debug build with scripts\\install.ps1.")
            }
            return ProviderState(standIn == "on", profile, copilot, id, credential)
        }
    }
}
