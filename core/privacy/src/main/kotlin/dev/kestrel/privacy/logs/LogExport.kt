package dev.kestrel.privacy.logs

import kotlinx.serialization.Serializable

/**
 * The file that an export writes: both logs with all their entries, oldest first, and times in UTC as ISO 8601
 * (design spec, section 13.5).
 */
@Serializable
data class LogExport(
    val exportedAt: String,
    val activity: List<Entry<ActivityEvent>>,
    val network: List<Entry<NetworkEvent>>,
) {
    @Serializable
    data class Entry<T>(val time: String, val event: T)
}
