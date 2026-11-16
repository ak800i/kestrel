package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Mode
import kotlinx.serialization.Serializable

/**
 * The listening mode that Kestrel had last, which it turns on in again after its process restarts (design spec,
 * sections 8.5 and 14.4), with the wall-clock times in milliseconds at which it was written ([writtenAt]) and at which
 * the last restore turned Kestrel on ([restoredAt]). A fresh install, and a value that this version cannot read, give
 * Off. A value that an earlier version stored has no times.
 */
@Serializable
data class ListeningSetting(val mode: Mode = Mode.OFF, val writtenAt: Long? = null, val restoredAt: Long? = null)
