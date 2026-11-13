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

    /** Plays or pauses media, like the button on a headset. */
    PLAY_PAUSE(AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK),
}
