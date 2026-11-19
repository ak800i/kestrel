package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Requests
import dev.kestrel.voice.Keyword
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

/**
 * The times on the phone's clock, [now] (SystemClock.elapsedRealtime in milliseconds, which the shell's /proc/uptime
 * also counts), of the events that the A71 session measures, so that no measure counts from the owner's Enter (plan 2E;
 * analysis of the A71 session of sub-project 2). The debug hook voice-times reads them through [describe]; plan 2E's
 * "Kestrel, open settings" also reads [settingsResumes] and [settingsInFront]. It holds times and the names of kinds,
 * never words. Any thread may use it.
 */
internal class Timeline(private val now: () -> Long) {
    /** An event of [kind] at [at]; [sinceSpeech] is how long after the end of the latest speech it came, or null. */
    data class Mark(val kind: String, val at: Long, val sinceSpeech: Long? = null)

    /** The latest event that turned Kestrel on or toggled it, by its source, such as "tile". */
    @Volatile
    var started: Mark? = null
        private set

    /** When the latest voice session opened, or 0. */
    @Volatile
    var opened = 0L
        private set

    /** The end of the latest transcribed utterance, as the speech detector found it, or 0. */
    @Volatile
    var speechEnded = 0L
        private set

    /**
     * Kestrel's latest reaction: the start of a wait for a task ("wait"), a mode ("mode-paused"), a task or a command.
     * Its [Mark.sinceSpeech] counts from the end of the latest transcribed speech, whatever caused the reaction, so a
     * reaction that no transcript caused, such as a spotter's hit while Kestrel speaks, counts from older speech; a
     * reader compares `at - sinceSpeech` with the start of its try.
     */
    @Volatile
    var reacted: Mark? = null
        private set

    /**
     * Why and when Kestrel last turned itself off, by the name of its StopReason: only a reason that turned a listening
     * or paused Kestrel off, not one that came while Kestrel was off or while an earlier turn-off was on its way.
     */
    @Volatile
    var stopped: Mark? = null
        private set

    /** When Kestrel last asked Android for its settings, or 0. */
    @Volatile
    var settingsAsked = 0L
        private set

    /** Whether Kestrel's settings are in front now: from MainActivity's onResume until its onPause. */
    @Volatile
    var settingsInFront = false
        private set

    private val resumes = MutableStateFlow(0L)

    /** When Kestrel's settings last came to the front, or 0. */
    val settingsResumes: StateFlow<Long> = resumes.asStateFlow()

    fun start(source: String) {
        started = Mark(source, now())
    }

    fun opened() {
        opened = now()
    }

    /** [at] is on the same clock; 0 means that the end is not known, which changes nothing. */
    fun speechEnded(at: Long) {
        if (at > 0) speechEnded = at
    }

    fun reacted(kind: String) {
        val at = now()
        val speech = speechEnded
        reacted = Mark(kind, at, if (speech > 0) at - speech else null)
    }

    fun stopped(reason: String) {
        stopped = Mark(reason, now())
    }

    fun settingsAsked() {
        settingsAsked = now()
    }

    fun settingsResumed() {
        settingsInFront = true
        resumes.value = now()
    }

    fun settingsPaused() {
        settingsInFront = false
    }

    /**
     * The answer of the hook voice-times: each time in milliseconds on the phone's clock, and 0 or none for what has
     * not happened in this process, such as "now=81234 started=tile@80012 opened=86010 speechEnded=0 reacted=none ...".
     */
    fun describe(): String =
        "now=${now()} started=${text(started)} opened=$opened speechEnded=$speechEnded reacted=${text(reacted)} " +
            "stopped=${text(stopped)} settingsAsked=$settingsAsked settingsResumed=${resumes.value} " +
            "settingsInFront=$settingsInFront"

    private fun text(mark: Mark?): String =
        mark?.let { "${it.kind}@${it.at}" + (it.sinceSpeech?.let { since -> "+$since" } ?: "") } ?: "none"
}

/**
 * What the voice sessions of this process heard, for the A71 session (plan 2E): the keyword spotter's hits by keyword,
 * the transcribed utterances, those at whose start the spotter heard the name, their audio in milliseconds, and how
 * Requests read each transcript, by the kind of request; every transcript of a voice session counts, also one during
 * a wait for a task or during a call. Counts only, never words. Any thread may use it.
 */
internal class VoiceCounts {
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    fun spotted(keyword: Keyword) = add("hit-${keyword.name.lowercase()}", 1)

    fun heard(event: Event.Heard) {
        add("utterances", 1)
        if (event.named) add("named", 1)
        add("audioMillis", (event.seconds * 1_000).roundToLong())
        val read = Requests.read(event.text, event.named)
        add("read-${read?.javaClass?.simpleName?.lowercase() ?: "empty"}", 1)
    }

    /** The counts above 0, sorted by name, such as "audioMillis=1750 hit-kestrel=1 named=1 ... utterances=2". */
    fun describe(): String = counts.entries
        .filter { it.value.get() > 0 }
        .sortedBy { it.key }
        .joinToString(" ") { "${it.key}=${it.value.get()}" }

    private fun add(key: String, amount: Long) {
        counts.getOrPut(key) { AtomicLong() }.addAndGet(amount)
    }
}
