package dev.kestrel.app.debug

/**
 * Who may use Kestrel's test hooks (design spec, section 16.1; plan 7G's amendment "The hooks' transport"): adb's shell
 * (uid 2000) and root (uid 0), and no one else. Every app is refused, also one that holds DUMP, such as SystemUI, an app
 * of the system uid or a privileged app of the phone's maker, and so is Kestrel's own process. [TestHookProvider] checks
 * the caller's uid by this rule on every entry point.
 */
internal object HookCallers {
    /** The uid of adb's shell, `content call` from `adb shell` and UiAutomation's shell commands (AOSP's Process.SHELL_UID). */
    const val SHELL_UID = 2000

    /** The uid of root, `adb root`'s shell (AOSP's Process.ROOT_UID). */
    const val ROOT_UID = 0

    /** The reason of a refusal, the message of the SecurityException that `content call` prints. */
    const val REFUSAL = "Kestrel's test hooks answer only adb's shell (uid 2000) and root (uid 0)"

    fun allowed(uid: Int): Boolean = uid == SHELL_UID || uid == ROOT_UID
}
