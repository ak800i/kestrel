package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Mode
import kotlinx.serialization.Serializable

/**
 * The listening mode that Kestrel had last, which it turns on in again after its process restarts (design spec, sections
 * 8.5 and 14.4). A fresh install, and a value that this version cannot read, give Off.
 */
@Serializable
data class ListeningSetting(val mode: Mode = Mode.OFF)
