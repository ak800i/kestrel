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

        /**
         * A press that may or may not have worked: Android answered the node action with false, although apps sometimes
         * act all the same, or did not report the end of a gesture (design D32 item 29 of sub-project 5). Kestrel did not
         * press again; the caller looks at the screen before it tries again.
         */
        val UNCERTAIN = Failed("The press may not have worked.")

        /** Kestrel performs no gesture and no node action while its confirmation card shows (design D32 item 20 of sub-project 5). */
        val CARD_OPEN = Failed("A confirmation question is open.")

        /** The element's node has left the screen during the action. */
        val GONE = Failed("It's no longer on the screen.")
    }
}
