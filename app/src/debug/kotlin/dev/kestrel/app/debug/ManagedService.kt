package dev.kestrel.app.debug

/**
 * Kestrel's accessibility service as Android's accessibility manager holds it, read from `dumpsys accessibility` for the
 * device tests' `ScreenServiceRule`: whether the manager has it [enabled], whether it is [binding] it, and whether it holds
 * it as [crashed]. The rule is the scripts' `ConvertFrom-AccessibilityDump` (`scripts\lib\Kestrel.ps1`), and both are
 * tested with the same sample dumps (`app\src\testDebug\resources\accessibility-dumps`; the whole-plan review's M8):
 * AOSP's `AccessibilityUserState.dump` prints a block `User state[` for each user, which starts with
 * `attributes:{id=<user>, …` and has the lines `Enabled services:{…}`, `Binding services:{…}` and `Crashed services:{…}`,
 * each with every component's full name in braces. Only the block of the user that the line `currentUserId=<user>` names
 * counts, or the first block when that line is missing, and only the component's exact name, `{package/class}`.
 */
internal data class ManagedService(val enabled: Boolean, val binding: Boolean, val crashed: Boolean) {
    /** Whether the manager has applied the service's off: it neither has it enabled, nor binds it, nor holds it as crashed. */
    val off: Boolean get() = !enabled && !binding && !crashed

    override fun toString(): String = "enabled=$enabled binding=$binding crashed=$crashed"

    companion object {
        /**
         * The state of [service] (`package/class`) in [dump], the output of `dumpsys accessibility`; null when the dump has
         * no block of the current user, so that a dump that could not be read never counts as a service that is off.
         */
        fun of(dump: String, service: String): ManagedService? {
            val lines = dump.lines()
            val current = lines.firstNotNullOfOrNull { CURRENT_USER.matchEntire(it)?.groupValues?.get(1) }
            val blocks = mutableListOf<MutableList<String>>()
            for (line in lines) {
                if (USER_STATE.containsMatchIn(line)) blocks += mutableListOf<String>() else blocks.lastOrNull()?.add(line.trim())
            }
            val block = blocks.firstOrNull { current == null || it.firstOrNull()?.startsWith("attributes:{id=$current,") == true }
                ?: return null
            fun listed(name: String) = block.any { it.startsWith("$name services:{") && "{$service}" in it }
            return ManagedService(enabled = listed("Enabled"), binding = listed("Binding"), crashed = listed("Crashed"))
        }

        private val CURRENT_USER = Regex("""^\s*currentUserId=(\d+)\s*$""")
        private val USER_STATE = Regex("""^\s*User state\[""")
    }
}

/** What `ScreenServiceRule` does when Kestrel's service has not connected within its bound after it was turned on. */
internal sealed interface ServiceStall {
    val message: String

    /** Turn the service off and on once more. */
    data class Retry(override val message: String) : ServiceStall

    /** Fail the test with [message]. */
    data class Fail(override val message: String) : ServiceStall

    companion object {
        /**
         * The step after Kestrel's service did not connect [seconds] after it was turned on, with the manager in [state],
         * after [retried] retries. A bind that never completes keeps the service in the manager's binding set, and the
         * manager then never binds it again; AOSP's `AccessibilityUserState.removeDisabledServicesFromTemporaryStatesLocked`
         * drops it from that set once the service is off ("services can get stuck in the binding state if binding starts
         * but never finishes"), and from its crashed set too. So a stuck bind or a crash is retried once, through an off that
         * the manager has applied; any other state, a state that could not be read, or a second stall, fails with what the
         * manager showed.
         */
        fun after(state: ManagedService?, seconds: Long, retried: Int): ServiceStall {
            val what = when {
                state == null -> "Android's accessibility manager's state could not be read $seconds s after Kestrel's service was turned on"
                state.binding -> "Android's accessibility manager has been binding Kestrel's service for $seconds s since it was turned on (binding stuck)"
                state.crashed -> "Android's accessibility manager holds Kestrel's service as crashed $seconds s after it was turned on"
                state.enabled -> "Android's accessibility manager has Kestrel's service enabled, not binding, and it did not connect within $seconds s"
                else -> "Android's accessibility manager does not have Kestrel's service enabled $seconds s after it was turned on"
            }
            val stuck = state != null && (state.binding || state.crashed)
            return if (stuck && retried == 0) Retry(what) else Fail(what)
        }
    }
}
