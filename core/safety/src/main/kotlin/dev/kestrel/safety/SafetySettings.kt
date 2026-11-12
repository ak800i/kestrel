package dev.kestrel.safety

import kotlinx.serialization.Serializable

/** Safety settings. Test mode is on until the owner turns it off for daily use (design spec, D25 and section 12.7). */
@Serializable
data class SafetySettings(val testMode: Boolean = true)
