package dev.kestrel.privacy.logs

import kotlinx.serialization.Serializable

/**
 * What kind of activity-log entry this is (design spec, section 13.5). The agent writes a model call's new part of the
 * request ([PROMPT]), its reply with the usage and the cost ([REPLY]) and each safety decision ([SAFETY]) beside its
 * actions, notes and errors (design D25 and D32 item 46 of sub-project 5).
 */
@Serializable
enum class ActivityKind { ACTION, NOTE, ERROR, PROMPT, REPLY, SAFETY }

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
