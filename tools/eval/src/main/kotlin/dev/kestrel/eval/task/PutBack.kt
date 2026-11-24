package dev.kestrel.eval.task

import java.net.URLEncoder

/**
 * What a run's restores found changed by the run (plan 6B-basefix T6), by setting name, for the run's detail line, in
 * three kinds that the line keeps apart:
 * - [applied] (`putBack=`): put back and in effect again at once, such as the display density that `wm density` applies;
 * - [stored] (`byHand=`): put back as stored values, which Android may show only after a reboot or when their own page
 *   sets them again, so the owner may have to set them again by hand;
 * - [leftChanged] (`leftChanged=`): not put back, because nothing outside the setting's owner can write it while that
 *   owner runs (such as SystemUI's record of the tiles that it added, from API 35), each with its value before the run.
 * A restore may record from the shutdown hook's thread.
 */
class PutBack {
    private val inEffect = mutableListOf<String>()
    private val storedOnly = mutableListOf<String>()
    private val notPutBack = mutableListOf<LeftChanged>()

    @Synchronized
    fun recordApplied(name: String) {
        inEffect += name
    }

    @Synchronized
    fun recordStored(name: String) {
        storedOnly += name
    }

    /** Records that the run changed [name] and that no restore could put it back; [old] is its value before, null for none. */
    @Synchronized
    fun recordLeftChanged(name: String, old: String?) {
        notPutBack += LeftChanged(name, old)
    }

    val applied: List<String>
        @Synchronized get() = inEffect.toList()

    val stored: List<String>
        @Synchronized get() = storedOnly.toList()

    val leftChanged: List<LeftChanged>
        @Synchronized get() = notPutBack.toList()
}

/**
 * A setting that the run changed and that no restore could put back, with its value before the run ([old], null when it
 * had none). Its detail-line form is `<name>:<old>`, with the old value percent-encoded so that it holds no space or
 * comma, or `<name>` alone when the setting had no value before the run.
 */
data class LeftChanged(val name: String, val old: String?) {
    override fun toString(): String = if (old == null) name else "$name:" + URLEncoder.encode(old, Charsets.UTF_8).replace("+", "%20")
}
