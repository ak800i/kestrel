package dev.kestrel.screen.act

/** How Kestrel performed an action (design spec, section 9.3). */
enum class ActionMethod {
    /** The element's own accessibility action. */
    ACTION,

    /** The action of the element's nearest parent that handles touches, where a finger's touch would go. */
    PARENT_ACTION,

    /** The element's action in a fresh snapshot, after its node had gone or refused the action (design spec, section 14.3). */
    RETRY_ACTION,
    GESTURE,

    /** Kestrel's accessibility input method, for apps that refuse the set-text action (design spec, section 9.7). */
    INPUT_METHOD,
    GLOBAL_ACTION,
    INTENT,
    MAGNIFICATION,
}

sealed interface ActionResult {
    data class Done(val method: ActionMethod) : ActionResult

    data class Failed(val reason: String) : ActionResult

    companion object {
        /** What `ScreenActions.scroll` gives when nothing on the screen scrolls in the asked direction. */
        val NOTHING_SCROLLS = Failed("Nothing on the screen scrolls that way.")

        /** A tap or a gesture on an element that windows above, such as the keyboard or a popup, cover everywhere. */
        val COVERED = Failed("Other windows cover it.")

        /**
         * The element that the key names now is not the one that the caller expected, because a key names a position
         * (design spec, section 9.2). The instant commands say "The screen changed. Say it again." for it.
         */
        val CHANGED = Failed("The element changed.")
    }
}
