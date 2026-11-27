package dev.kestrel.screen.act

/**
 * A caller's view of the moment that a press of [ScreenActions.tap] or [ScreenActions.longPress] goes to Android, such as
 * the debug hooks' claim of their press of a notification's button (plan 7G). Both steps run on the main thread or on the
 * caller's coroutine, never at the same time.
 */
interface PressDispatch {
    /**
     * Runs right before Kestrel hands a press to Android: the node's action, or the first part of the fallback gesture,
     * inside [GestureRunner]'s turn. From then on the press may reach the app, whatever the press answers later, also when
     * it is cancelled or throws. A press that stops before, as one that a check refuses or one that is cancelled while it
     * waits for its turn, never runs it.
     */
    fun dispatching()

    /**
     * Runs right after a [dispatching] whose press Android certainly did not take: a gesture that Android refused, or
     * whose hand-over failed. A node's action that answers no never runs it, because some apps act all the same.
     */
    fun notDispatched()
}
