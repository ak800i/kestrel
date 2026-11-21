package dev.kestrel.agent

/**
 * One task as the owner spoke it (design D1 and D3). [id] is `task-<n>-<8 hex characters>`, which `:app` makes and which
 * also names the task in the activity log and the network log (D25). [knownNames] are the contact names that `:app` read
 * on the phone when Kestrel has the contacts permission, for the task's allowance (D13); no screen text is among them.
 * toString prints only lengths and counts (D25).
 */
data class TaskRequest(val id: String, val text: String, val knownNames: Set<String> = emptySet()) {
    override fun toString(): String = "TaskRequest(idLength=${id.length}, textLength=${text.length}, knownNames=${knownNames.size})"
}
