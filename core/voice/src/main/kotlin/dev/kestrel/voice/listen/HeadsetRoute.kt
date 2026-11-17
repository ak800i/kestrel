package dev.kestrel.voice.listen

/**
 * A Bluetooth headset that Android offers as a communication device. [id] is the ID of its output device. [leAudio]: an
 * LE Audio headset, whose microphone Android gives the recording only when the recording also asks for it as its
 * preferred device (research r12, item 4); otherwise a classic headset, which Android reaches through SCO.
 */
data class Headset(val id: Int, val leAudio: Boolean)

/** The part of Android's AudioManager that [HeadsetRoute] uses. AndroidCommunicationDevices calls Android; tests use a fake. */
interface CommunicationDevices {
    /** The Bluetooth headsets, classic or LE Audio, among the communication devices that Android offers now. */
    fun headsets(): List<Headset>

    /**
     * Asks Android to use [headset] as the communication device, whose microphone then also records the source
     * VOICE_RECOGNITION. Returns false when Android refuses, for example because the headset went away meanwhile.
     */
    fun choose(headset: Headset): Boolean

    /** Withdraws Kestrel's request, so that Android's own choice counts again. */
    fun clear()

    /**
     * Calls [changed] after each change of the communication device, with the new device's ID or null for none, until the
     * returned handle is closed. [changed] may come on another thread.
     */
    fun watch(changed: (Int?) -> Unit): AutoCloseable
}

/**
 * Routes Kestrel's recording to the microphone of connected Bluetooth earbuds while the owner allows it (design spec,
 * section 8.8; research r12). [open] asks Android for an LE Audio headset, or else for a classic one, as the
 * communication device, and [close] withdraws the request. When Android reports another communication device, because
 * the headset went away or a call took the audio over, the route withdraws its request and calls the `onLost` of
 * [open], so that the recording goes back to the phone's microphone; it asks again only at the next [open]. It never
 * sets Android's audio mode, so a call keeps its own route (r12, item 7). Any thread may call [open] and [close], one at
 * a time.
 */
class HeadsetRoute(private val devices: CommunicationDevices) {
    private val lock = Any()
    // The open that holds the request. Each open has its own token, so that a late call of an earlier open's watcher,
    // which Android may still deliver after the close, changes nothing.
    private var held: Any? = null
    private var watching: AutoCloseable? = null

    /**
     * Asks for a connected headset and returns it, or returns null when no headset is connected or Android refused it;
     * the phone's microphone then records as before. [onLost] runs at most once, on the thread of Android's report, when
     * the route has given the headset up on its own; [close] waits for it. Throws [IllegalStateException] while a
     * headset is asked for, and rethrows a failure to watch Android's reports after it has withdrawn the request.
     */
    fun open(onLost: () -> Unit): Headset? {
        synchronized(lock) {
            check(held == null) { "A headset is already asked for" }
            val headsets = devices.headsets()
            // LE Audio keeps the quality of the sound, while a classic headset switches to its call profile (r12, item 5).
            val headset = headsets.firstOrNull { it.leAudio } ?: headsets.firstOrNull() ?: return null
            if (!devices.choose(headset)) return null
            val token = Any()
            held = token
            // Android delivers its reports asynchronously, so a report of an earlier change, such as the clear of the last
            // close, could still arrive and withdraw this request; the callers leave time between a stop and the next
            // start, except when the owner's choice changes, and then only one of the two opens a route. Android also
            // reports the headset itself once its link is up, which changes nothing.
            watching = try {
                devices.watch { id -> if (id != headset.id) lose(token, onLost) }
            } catch (e: Exception) {
                // Without a watcher, nothing would ever withdraw the request.
                held = null
                devices.clear()
                throw e
            }
            return headset
        }
    }

    /** Withdraws the request of [open], if there is one. */
    fun close() {
        val watch = synchronized(lock) {
            if (held == null) return
            held = null
            watching.also { watching = null }
        }
        watch?.close()
        devices.clear()
    }

    // Runs whole under the lock, so that a close that comes meanwhile waits until the withdrawal and onLost are done.
    // The next open, of this route or of the next recording's, comes after that close, so neither the withdrawal nor
    // onLost reaches the request or the recording of a later open.
    private fun lose(token: Any, onLost: () -> Unit) {
        synchronized(lock) {
            // A close, or the open after it, came first.
            if (held !== token) return
            held = null
            watching?.close()
            watching = null
            devices.clear()
            onLost()
        }
    }
}
