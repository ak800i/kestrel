package dev.kestrel.agent.goal

import kotlinx.serialization.Serializable

/**
 * One round's limits (D7 and D22 of sub-project 7): model replies, Kestrel's working minutes, and AI Credits for a profile
 * that counts them. The defaults are the owner's (design 7.4); `:core:conversation` gives each round the limits of the
 * Conversations page with its plan.
 */
@Serializable
data class GoalLimits(val replies: Int = 500, val workMinutes: Int = 60, val credits: Int = 1000) {
    init {
        require(replies > 0 && workMinutes > 0 && credits > 0) { "A round's limits must be positive" }
    }
}
