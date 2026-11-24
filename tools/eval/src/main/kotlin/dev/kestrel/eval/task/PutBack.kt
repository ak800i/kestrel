package dev.kestrel.eval.task

/**
 * The settings that a run's restores found changed by the run and put back (plan 6B-basefix T6), by name, for the run's
 * detail line: those that are in effect again at once ([applied]), such as the display density that `wm density` applies,
 * and those whose stored value went back but which Android may show only after a reboot or when their page sets them
 * again ([stored]), which the owner may have to set by hand. A restore may record from the shutdown hook's thread.
 */
class PutBack {
    private val inEffect = mutableListOf<String>()
    private val storedOnly = mutableListOf<String>()

    @Synchronized
    fun recordApplied(name: String) {
        inEffect += name
    }

    @Synchronized
    fun recordStored(name: String) {
        storedOnly += name
    }

    val applied: List<String>
        @Synchronized get() = inEffect.toList()

    val stored: List<String>
        @Synchronized get() = storedOnly.toList()
}
