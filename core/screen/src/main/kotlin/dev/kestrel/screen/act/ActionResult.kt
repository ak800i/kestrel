package dev.kestrel.screen.act

/** How Kestrel performed an action (design spec, section 9.3). */
enum class ActionMethod {
    /** The element's own accessibility action. */
    ACTION,

    /** The action of the element's nearest parent that offers it. */
    PARENT_ACTION,

    /** The element's action in a fresh snapshot, after its node had gone or refused the action (design spec, section 14.3). */
    RETRY_ACTION,
    GESTURE,
}

sealed interface ActionResult {
    data class Done(val method: ActionMethod) : ActionResult

    data class Failed(val reason: String) : ActionResult
}
