package dev.kestrel.voice.listen

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    /** The ID of Android's communication device now, or null for none. */
    fun current(): Int?

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
 * sets Android's audio mode, so a call keeps its own route (r12, item 7). [open] waits up to [linkMillis] until Android
 * names the headset as its communication device, because Android 13 does so for a classic headset only once its SCO
 * link is up, and a recording that opens before that may stay on the phone's microphone (A71 session of sub-project 2,
 * item 6). After the limit the request stays, and a link that comes later counts only from the next [open]. [open]
 * blocks its caller for up to [linkMillis], so it must not run on the main thread, which delivers Android's reports;
 * any thread may call [close]; one call at a time.
 */
class HeadsetRoute(private val devices: CommunicationDevices, private val linkMillis: Long = LINK_MILLIS) {
    private val lock = Any()
    // The open that holds the request. Each open has its own token, so that a late call of an earlier open's watcher,
    // which Android may still deliver after the close, changes nothing.
    private var held: Any? = null
    private var watching: AutoCloseable? = null

    /**
     * Asks for a connected headset and returns it, or returns null when no headset is connected or Android refused it;
     * the phone's microphone then records as before. [onLost] runs at most once, on the thread of Android's report, when
     * the route has given the headset up on its own; [close] waits for it. After the request, it waits up to [linkMillis]
     * until Android names the headset as its communication device, and returns the headset also when Android does not
     * name it in time. Throws [IllegalStateException] while a headset is asked for, and rethrows a failure to watch
     * Android's reports, or a failure to ask Android for its current communication device, after it has withdrawn the
     * request.
     */
    fun open(onLost: () -> Unit): Headset? {
        // Ends the wait below: the headset's own report, which names it, or a report of another device, after which the
        // request is withdrawn.
        val reported = CountDownLatch(1)
        val named = AtomicBoolean(false)
        val headset = synchronized(lock) {
            check(held == null) { "A headset is already asked for" }
            linkedMillis = null
            val headsets = devices.headsets()
            // LE Audio keeps the quality of the sound, while a classic headset switches to its call profile (r12, item 5).
            val headset = headsets.firstOrNull { it.leAudio } ?: headsets.firstOrNull() ?: return null
            if (!devices.choose(headset)) return null
            val token = Any()
            held = token
            // Android delivers its reports asynchronously, so a report of an earlier change, such as the clear of the last
            // close, could still arrive and withdraw this request; the callers leave time between a stop and the next
            // start, except when the owner's choice changes, and then only one of the two opens a route. Android reports
            // the headset itself once its link is up, which ends the wait below.
            watching = try {
                devices.watch { id ->
                    if (id == headset.id) named.set(true) else lose(token, onLost)
                    reported.countDown()
                }
            } catch (e: Exception) {
                // Without a watcher, nothing would ever withdraw the request. A failure of the withdrawal too is kept with
                // the watcher's.
                held = null
                runCatching { devices.clear() }.exceptionOrNull()?.let(e::addSuppressed)
                throw e
            }
            headset
        }
        // Without the lock, so that a loss can still come. The current device covers a report that came before the
        // watcher was there.
        val started = System.nanoTime()
        try {
            if (devices.current() == headset.id) named.set(true) else reported.await(linkMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // The headset counts as not named, and the caller keeps its interrupt.
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            // A failure of Android's audio service: the request is withdrawn, as after a failure of the watcher.
            runCatching { close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        if (named.get()) linkedMillis = (System.nanoTime() - started) / 1_000_000
        return headset
    }

    /**
     * How long the latest [open] waited until Android named the headset as its communication device, in milliseconds, or
     * null when it did not within [linkMillis]; for the debug hook voice-route (plan 2E).
     */
    @Volatile
    var linkedMillis: Long? = null
        private set

    /** Whether a request of [open] holds: until [close], or until the route gave the headset up on its own. */
    val holding: Boolean get() = synchronized(lock) { held != null }

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

    private companion object {
        // How long a start waits for the headset's link. The A71 session's linkedMillis shows whether it must grow.
        const val LINK_MILLIS = 3_000L
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
