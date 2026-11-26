package dev.kestrel.app.phone

import dev.kestrel.app.listen.CallMonitor
import dev.kestrel.app.orchestrator.CallState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean

/** A watch of the phone that one part of Kestrel holds; [stop] ends it, and a second stop changes nothing. */
internal fun interface PhoneWatch {
    fun stop()
}

/**
 * What the screen source reads (design spec, section 8.5): the screen is off, on over the lock screen, or on with the
 * phone unlocked. It is the one reading of the lock for every part of Kestrel (plan 7F's whole-plan re-check).
 */
internal enum class ScreenReading {
    OFF,
    LOCKED,
    UNLOCKED,
    ;

    /**
     * Whether the screen is on and the phone unlocked, which listening and the conversation count as the owner's
     * presence.
     */
    val unlocked: Boolean get() = this == UNLOCKED
}

/**
 * Android's screen and lock (design spec, section 8.5). Any thread may call it. Every report, every [start]'s cameOn and
 * every [read] runs on the source's one thread, in the order in which they happen there, so that no reading can come
 * after a newer change.
 */
internal interface ScreenSource {
    /**
     * Starts to report every change of the [ScreenReading] to [report]; and each time the screen comes on, locked or
     * not, to [cameOn], after [report].
     */
    fun start(report: (ScreenReading) -> Unit, cameOn: () -> Unit)

    /** Stops the reports. */
    fun stop()

    /**
     * Gives [reading] the [ScreenReading], read on the source's thread: at once when the caller is on it, otherwise
     * after the reports that are already on their way there.
     */
    fun read(reading: (ScreenReading) -> Unit)
}

/**
 * The phone's screen, lock and calls for every part of Kestrel that watches them (design 5.5 and 6.5 of sub-project 7):
 * listening while its service runs, and from plan 7D's Task 3 the conversation while it is open, so that a goal pauses on
 * a lock or a call also with listening off. One screen receiver and one call listener serve every watch: each starts
 * with its first watch and stops with its last. A new screen watch gets its first reading on the screen source's thread,
 * as every change, so that the reading never comes after a newer change. Every new call watch registers Android's
 * listener again, which reports the state at once, as [CallMonitor] promises at each start, so that a watch that starts
 * after the owner allowed the phone state sees the calls. Any thread may call it; a screen listener is called on the
 * screen source's thread and a call listener on Android's, and each must return at once.
 */
internal class PhoneSignals(private val screens: ScreenSource, private val calls: CallMonitor) {
    // One object per watch, so that the same listener given twice is watched twice and stopped once each.
    private class Watcher<T>(val listener: (T) -> Unit)

    private class ScreenWatcher(val listener: (ScreenReading) -> Unit, val cameOn: () -> Unit)

    private val lock = Any()
    private val screenWatchers = mutableListOf<ScreenWatcher>()
    private val callWatchers = mutableListOf<Watcher<CallState>>()

    /**
     * Watches the screen: [listener] gets the reading first and then every change, and [cameOn] hears each time the screen
     * comes on, locked or not, until the watch stops. On the screen source's thread the first reading comes at once,
     * otherwise a moment later; a watch that stops before it gets none. Throws what [ScreenSource.start] throws, and then
     * holds no watch. [listener] comes last, so that a trailing lambda is the reading's.
     */
    fun watchScreen(cameOn: () -> Unit = {}, listener: (ScreenReading) -> Unit): PhoneWatch {
        val watcher = ScreenWatcher(listener, cameOn)
        synchronized(lock) {
            screenWatchers += watcher
            if (screenWatchers.size == 1) {
                try {
                    screens.start(::screenChanged, ::screenCameOn)
                } catch (e: Exception) {
                    screenWatchers.remove(watcher)
                    throw e
                }
            }
        }
        screens.read { reading -> if (synchronized(lock) { watcher in screenWatchers }) listener(reading) }
        return once {
            synchronized(lock) { if (screenWatchers.remove(watcher) && screenWatchers.isEmpty()) screens.stop() }
        }
    }

    /**
     * Watches the calls: [listener] gets Android's report of the state at the start and every change, until the watch
     * stops. Throws what [CallMonitor.start] throws, and then holds no watch.
     */
    fun watchCalls(listener: (CallState) -> Unit): PhoneWatch {
        val watcher = Watcher(listener)
        synchronized(lock) {
            callWatchers += watcher
            try {
                calls.start(::callChanged)
            } catch (e: Exception) {
                callWatchers.remove(watcher)
                throw e
            }
        }
        return once {
            synchronized(lock) { if (callWatchers.remove(watcher) && callWatchers.isEmpty()) calls.stop() }
        }
    }

    /**
     * The call states while the flow is collected (design D15 of sub-project 5): a call hand-over ends by itself when the
     * owner places the call, with listening on or off. The flow fails with what [watchCalls] throws.
     */
    fun callStates(): Flow<CallState> = callbackFlow {
        val watch = watchCalls { state -> trySend(state) }
        awaitClose { watch.stop() }
    }

    /**
     * The [ScreenReading] while the flow is collected: the reading first, then every change ([watchScreen]). A question
     * card's Continue counts only on [ScreenReading.UNLOCKED], and one that the owner tapped on the lock screen waits
     * on it for his unlock or the screen's end (plan 7F). The flow fails with what [watchScreen] throws.
     */
    fun screenStates(): Flow<ScreenReading> = callbackFlow {
        val watch = watchScreen { reading -> trySend(reading) }
        awaitClose { watch.stop() }
    }

    /**
     * The calls as one [CallMonitor] for listening (section 8.7): a start watches the calls with its report and ends the
     * watch of an earlier start, after the new one has begun, so that Android's listener never stops in between; a stop
     * ends the watch.
     */
    fun callMonitor(): CallMonitor = object : CallMonitor {
        private var watch: PhoneWatch? = null

        override fun start(report: (CallState) -> Unit) {
            val started = watchCalls(report)
            synchronized(this) { watch.also { watch = started } }?.stop()
        }

        override fun stop() {
            synchronized(this) { watch.also { watch = null } }?.stop()
        }
    }

    private fun screenChanged(reading: ScreenReading) {
        synchronized(lock) { screenWatchers.toList() }.forEach { it.listener(reading) }
    }

    private fun screenCameOn() {
        synchronized(lock) { screenWatchers.toList() }.forEach { it.cameOn() }
    }

    private fun callChanged(state: CallState) {
        synchronized(lock) { callWatchers.toList() }.forEach { it.listener(state) }
    }

    private fun once(stop: () -> Unit): PhoneWatch {
        val stopped = AtomicBoolean()
        return PhoneWatch { if (stopped.compareAndSet(false, true)) stop() }
    }

    override fun toString(): String = "PhoneSignals"
}
