package dev.kestrel.app.debug

/**
 * Kestrel's accessibility service as Android's accessibility manager holds it, read from `dumpsys accessibility` for the
 * device tests' `ScreenServiceRule`: whether the manager has it [enabled] and whether it is [binding] it. AOSP's
 * `AccessibilityUserState.dump` prints `Enabled services:{…}` and `Binding services:{…}` on one line each, with each
 * component's full name; nothing else of the dump is read.
 */
internal data class ManagedService(val enabled: Boolean, val binding: Boolean) {
    /** Whether the manager has applied the service's off: it neither has it enabled nor binds it. */
    val off: Boolean get() = !enabled && !binding

    override fun toString(): String = "enabled=$enabled binding=$binding"

    companion object {
        /** The state of [service] (`package/class`) in [dump], the output of `dumpsys accessibility`. */
        fun of(dump: String, service: String): ManagedService {
            val lines = dump.lineSequence().map { it.trim() }.toList()
            fun listed(name: String) = lines.any { it.startsWith("$name services:") && service in it }
            return ManagedService(enabled = listed("Enabled"), binding = listed("Binding"))
        }
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
         * but never finishes"). So a stuck bind is retried once, through an off that the manager has applied; any other
         * state, or a second stall, fails with what the manager showed.
         */
        fun after(state: ManagedService, seconds: Long, retried: Int): ServiceStall {
            val what = when {
                state.binding -> "Android's accessibility manager has been binding Kestrel's service for $seconds s since it was turned on (binding stuck)"
                state.enabled -> "Android's accessibility manager has Kestrel's service enabled, not binding, and it did not connect within $seconds s"
                else -> "Android's accessibility manager does not have Kestrel's service enabled $seconds s after it was turned on"
            }
            return if (state.binding && retried == 0) Retry(what) else Fail(what)
        }
    }
}
