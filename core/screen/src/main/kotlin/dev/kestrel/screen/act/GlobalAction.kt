package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService

/** The global actions that Kestrel offers (design spec, section 9.3). */
enum class GlobalAction(internal val id: Int) {
    BACK(AccessibilityService.GLOBAL_ACTION_BACK),
    HOME(AccessibilityService.GLOBAL_ACTION_HOME),
    RECENT_APPS(AccessibilityService.GLOBAL_ACTION_RECENTS),
    NOTIFICATIONS(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
    QUICK_SETTINGS(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
    LOCK_SCREEN(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN),
    SCREENSHOT(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT),
    POWER_MENU(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG),
    SPLIT_SCREEN(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN),

    /** Plays or pauses media, like the button on a headset. During a call, the same key answers or ends the call. */
    PLAY_PAUSE(AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK),

    /** The launcher's list of all apps, where the launcher offers it (design spec, Appendix A, "Open apps"). */
    ALL_APPS(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS),

    /** Closes the notification shade and Quick Settings. */
    DISMISS_SHADE(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE),
    ;

    /** What the feedback bar shows when Android refuses the action: the action in plain words, never the enum's name. */
    internal val refused: ActionResult.Failed
        get() = ActionResult.Failed(
            "Android refused to " + when (this) {
                BACK -> "go back"
                HOME -> "go home"
                RECENT_APPS -> "show recent apps"
                NOTIFICATIONS -> "show the notifications"
                QUICK_SETTINGS -> "show Quick Settings"
                LOCK_SCREEN -> "lock the screen"
                SCREENSHOT -> "take a screenshot"
                POWER_MENU -> "open the power menu"
                SPLIT_SCREEN -> "split the screen"
                PLAY_PAUSE -> "play or pause"
                ALL_APPS -> "show all apps"
                DISMISS_SHADE -> "close the notifications"
            } + ".",
        )
}
