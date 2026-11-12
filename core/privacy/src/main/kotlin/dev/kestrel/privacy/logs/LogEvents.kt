package dev.kestrel.privacy.logs

import kotlinx.serialization.Serializable

/** What kind of activity-log entry this is (design spec, section 13.5). Later sub-projects add more kinds. */
@Serializable
enum class ActivityKind { ACTION, NOTE, ERROR }

@Serializable
data class ActivityEvent(
    val kind: ActivityKind,
    val text: String,
    val taskId: String? = null,
)

@Serializable
enum class NetworkOutcome { COMPLETED, FAILED, BLOCKED }

/** One request in the network log. It never contains the query string, headers or bodies. */
@Serializable
data class NetworkEvent(
    val method: String,
    val host: String,
    val path: String,
    val requestBytes: Long,
    val responseBytes: Long,
    val status: Int? = null,
    val outcome: NetworkOutcome,
    val taskId: String? = null,
    val detail: String? = null,
)
