package dev.kestrel.voice.speak

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import dev.kestrel.voice.Synthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger

/** How a reply ended. */
enum class Spoke {
    /** Kestrel spoke the whole reply. */
    FINISHED,

    /** [VoicePlayer.stop] or [VoicePlayer.close] ended it early, or before it began. */
    STOPPED,

    /**
     * Android refused audio focus, so Kestrel spoke nothing: in the background without a suitable foreground service
     * (B5), or while a call rings or lasts (prototype P3).
     */
    REFUSED,

    /**
     * Android took Kestrel's audio focus away while the reply played or waited, as for a ringing call or an app that asks
     * for exclusive focus, so Kestrel stopped it as [VoicePlayer.stop] would (design spec, section 14.6). The caller shows
     * its text instead.
     */
    INTERRUPTED,
}

/**
 * Speaks replies in Kestrel's voice (design spec, section 8.4). It speaks sentence by sentence and synthesizes the next
 * sentence while one plays, so that it starts soon and [stop] takes effect at once. The audio has the usage "assistant",
 * and other apps lower their volume while Kestrel speaks. Kestrel keeps its audio focus between the replies of a queue
 * and gives it up once no reply waits. Android 17 refuses audio focus to an app in the background without a suitable
 * foreground service (B5), and to every app while a call rings or lasts (prototype P3); then the player speaks nothing
 * and returns [Spoke.REFUSED]. When another app only lets Kestrel lower its volume, as for a notification's sound, the
 * player speaks on more quietly until the focus comes back. When Android takes the focus away while Kestrel speaks, for
 * a ringing call or an app that asks for exclusive focus, the player stops at once, and the replies that played or
 * waited return [Spoke.INTERRUPTED]. One reply plays at a time, in the order of the [speak] calls.
 *
 * The player skips every sentence that has no letter and no digit, such as "…", "!?" or an emoji, because there is
 * nothing in it to say, and sherpa-onnx would write such a sentence to the log (design spec, section 13.5).
 */
class VoicePlayer(context: Context, private val synthesizer: Synthesizer) {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = ReplyFocus(AndroidAudioFocus(context, attributes) { change -> focusChanged(change) })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val turn = Mutex()
    private val generation = AtomicInteger()
    private val history = ArrayDeque<Spoken>()
    private val lock = Any()
    private var track: AudioTrack? = null
    private var clips: Channel<Clip>? = null

    // The generations whose replies a loss of the audio focus ended, and the replies that were asked for and have not
    // ended yet; the set is emptied when none is left. Only code that holds [lock] uses them.
    private val interruptedGenerations = mutableSetOf<Int>()
    private var unfinished = 0

    // The synthesis of the reply that played last. Only the reply that holds the turn reads or sets it.
    private var synthesis: Job? = null

    /** The sentences that Kestrel played in the last few seconds, for the echo filter. Any thread may call it. */
    fun recent(now: Long = SystemClock.elapsedRealtime()): List<Spoken> = synchronized(history) {
        forget(now)
        history.toList()
    }

    /**
     * Speaks [text] after the replies that came before it, and completes when it has been spoken, stopped, interrupted
     * or refused, or with the error when synthesis or the audio output fails. A [stop] after this call ends the reply,
     * also when it has not begun yet. A reply whose sentences all lack letters and digits plays nothing and completes
     * with [Spoke.FINISHED]. After [close], the reply completes with [Spoke.STOPPED] at once.
     */
    fun speak(text: String): Deferred<Spoke> {
        // A cancelled Deferred would throw CancellationException into a caller that is not cancelled itself.
        if (!scope.isActive) return CompletableDeferred(Spoke.STOPPED)
        // Undispatched, the caller's thread takes the turn or joins the queue for it, so replies keep the order of the calls.
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            val mine = synchronized(lock) {
                unfinished++
                generation.get()
            }
            // Counted before the reply waits for its turn, so that the focus stays while it waits (section 8.4).
            focus.asked()
            try {
                val spoke = turn.withLock {
                    yield()
                    if (generation.get() != mine) return@withLock Spoke.STOPPED
                    // The synthesizer serves one sentence at a time, and a stopped reply's last sentence may still be
                    // in it.
                    synthesis?.join()
                    val sentences = Sentences.split(text).filter { sentence -> sentence.any(Char::isLetterOrDigit) }
                    when {
                        generation.get() != mine -> Spoke.STOPPED
                        sentences.isEmpty() -> Spoke.FINISHED
                        !focus.take() -> Spoke.REFUSED
                        else -> withTrack { output -> play(output, sentences, mine) }
                    }
                }
                // Section 14.6: a reply that a loss of the focus ended goes to the bar.
                if (spoke == Spoke.STOPPED && wasInterrupted(mine)) Spoke.INTERRUPTED else spoke
            } finally {
                synchronized(lock) { if (--unfinished == 0) interruptedGenerations.clear() }
                focus.finish()
            }
        }
    }

    /** Stops the reply that plays and the replies that were asked for before. Any thread may call it. */
    fun stop() {
        synchronized(lock) { endGeneration() }
    }

    /**
     * The volume at which a reply plays now: lower while another app's sound plays over Kestrel (section 8.4), and 1
     * otherwise. The device tests read it. Any thread may call it.
     */
    fun volume(): Float = focus.volume()

    // Section 8.4: another app that only lets Kestrel lower its volume, such as a notification's sound, makes the reply
    // quieter until the focus comes back. Section 14.6: when Android takes the focus away, for a call or an app that asks
    // for exclusive focus, the replies that play or wait end as a stop ends them, and they count as interrupted. Kestrel
    // gives the focus up only after the generation has ended, so that a queued reply whose turn comes in between does not
    // ask Android for it again. Android calls it on its focus callback thread, which is usually the main thread.
    private fun focusChanged(change: Int) {
        when (focus.changed(change)) {
            FocusChange.NONE -> Unit
            FocusChange.LOWER, FocusChange.RESTORE -> synchronized(lock) { track?.setVolume(focus.volume()) }
            FocusChange.STOP -> synchronized(lock) {
                interruptedGenerations += generation.get()
                endGeneration()
                focus.lost()
            }
        }
    }

    // A reply that reads the new generation hands over its channel and track only after the caller's section, so a stop
    // never ends a reply that was asked for after it. The caller holds [lock].
    private fun endGeneration() {
        generation.incrementAndGet()
        clips?.close()
        track?.run {
            pause()
            flush()
        }
    }

    private fun wasInterrupted(mine: Int): Boolean = synchronized(lock) { mine in interruptedGenerations }

    /**
     * Stops speaking, and returns when the synthesizer is no longer in use, so that its owner can close it. The player
     * cannot speak afterwards: a later [speak] completes with [Spoke.STOPPED] and does not use the synthesizer.
     */
    suspend fun close() {
        stop()
        scope.coroutineContext.job.cancelAndJoin()
    }

    private class Clip(val text: String, val samples: FloatArray)

    private suspend fun withTrack(speak: suspend (AudioTrack) -> Spoke): Spoke {
        val rate = synthesizer.sampleRate
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val output = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, rate / 5 * Float.SIZE_BYTES))
            .build()
        synchronized(lock) {
            track = output
            // A reply that starts while another app speaks over Kestrel starts quietly too (section 8.4).
            output.setVolume(focus.volume())
        }
        try {
            return speak(output)
        } finally {
            synchronized(lock) { track = null }
            output.pause()
            output.flush()
            output.release()
        }
    }

    private suspend fun play(output: AudioTrack, sentences: List<String>, mine: Int): Spoke {
        val rate = synthesizer.sampleRate
        // The synthesis job synthesizes the next sentence while one plays, and then waits until the player takes it, so
        // it runs exactly one sentence ahead. stop() closes the channel, so that the reply ends without waiting for the
        // sentence in synthesis.
        val ready = Channel<Clip>(Channel.RENDEZVOUS)
        synchronized(lock) { clips = ready }
        if (generation.get() != mine) ready.close()
        synthesis = scope.launch {
            var failure: Exception? = null
            try {
                for (sentence in sentences) {
                    if (generation.get() != mine) break
                    ready.send(Clip(sentence, synthesizer.synthesize(sentence)))
                }
            } catch (e: ClosedSendChannelException) {
                // stop() ended the reply.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            } finally {
                ready.close(failure)
            }
        }
        val played = mutableListOf<Spoken>()
        var finished = false
        try {
            output.play()
            var written = 0L
            for (clip in ready) {
                if (generation.get() != mine) break
                val start = playTime(output, written, rate)
                val spoken = Spoken(clip.text, start, start + clip.samples.size * 1_000L / rate)
                played += spoken
                synchronized(history) {
                    forget(SystemClock.elapsedRealtime())
                    history.addLast(spoken)
                }
                written += write(output, clip.samples, mine)
            }
            if (generation.get() == mine) {
                // A stream track starts only when its buffer is full, and holds back its last frames until more come, so a
                // buffer of silence follows the reply. AudioTrack.stop() would reset playbackHeadPosition to 0 at once.
                write(output, FloatArray(output.bufferSizeInFrames), mine)
                // The reply has played when Android took its last frame from the track and that frame reached the speaker.
                val end = playTime(output, written, rate)
                val deadline = end + DRAIN_MARGIN_MILLIS
                while (
                    generation.get() == mine &&
                    (output.playbackHeadPosition < written || SystemClock.elapsedRealtime() < end) &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    delay(20)
                }
            }
            finished = generation.get() == mine
        } finally {
            synchronized(lock) { clips = null }
            // A synthesis job that waits to hand over a sentence must not wait for ever when the reply ends early.
            ready.cancel()
            // A reply that was stopped, cancelled or failed did not play the rest of its sentences.
            if (!finished) cut(played, SystemClock.elapsedRealtime())
        }
        return if (finished) Spoke.FINISHED else Spoke.STOPPED
    }

    /** Drops the sentences that ended more than [KEEP_MILLIS] before [now]. The caller holds the lock on [history]. */
    private fun forget(now: Long) {
        while (history.isNotEmpty() && history.first().endMillis < now - KEEP_MILLIS) history.removeFirst()
    }

    /** Cuts the [played] sentences in the history at [now]: those that began keep the part that played, the rest go. */
    private fun cut(played: List<Spoken>, now: Long) = synchronized(history) {
        val entries = history.listIterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (played.none { it === entry }) continue
            val part = entry.playedBy(now)
            if (part == null) entries.remove() else entries.set(part)
        }
    }

    /**
     * When the frame after the first [written] frames will play, on the elapsed-time clock. The playback head counts the
     * frames that Android took from the track, so it is always ahead of the speaker by the output latency, and further
     * ahead at the start, while Android fills its buffers; the time that it gives is too early. The track's timestamp
     * tells when a frame reached the speaker, so while the track plays without a gap it gives the true time, if the
     * device reports it correctly. A wrong timestamp must not delay [speak] or move the echo filter's times by more than
     * a real output latency, so the timestamp counts only up to [MAX_LATENCY_MILLIS] after the head's time.
     */
    private fun playTime(output: AudioTrack, written: Long, rate: Int): Long {
        val now = SystemClock.elapsedRealtime()
        val byHead = now + (written - output.playbackHeadPosition) * 1_000 / rate
        val stamp = AudioTimestamp()
        if (!output.getTimestamp(stamp)) return byHead
        // The timestamp uses the monotonic clock, which unlike the elapsed-time clock stops while the phone sleeps.
        val reached = now + (stamp.nanoTime - System.nanoTime()) / 1_000_000
        val byStamp = reached + (written - stamp.framePosition) * 1_000 / rate
        return byStamp.coerceIn(byHead, byHead + MAX_LATENCY_MILLIS)
    }

    /**
     * Writes [samples] in chunks of 0.1 s, so that a stop or a cancellation takes effect quickly, and returns how many it
     * wrote. Throws [IllegalStateException] when the track reports an error.
     */
    private suspend fun write(output: AudioTrack, samples: FloatArray, mine: Int): Int {
        val chunk = synthesizer.sampleRate / 10
        var offset = 0
        while (offset < samples.size && generation.get() == mine) {
            currentCoroutineContext().ensureActive()
            val count = output.write(samples, offset, minOf(chunk, samples.size - offset), AudioTrack.WRITE_BLOCKING)
            check(count >= 0) { "AudioTrack.write failed with $count" }
            // pause() interrupted the write.
            if (count == 0) break
            offset += count
        }
        return offset
    }

    private companion object {
        const val KEEP_MILLIS = 5_000L
        const val DRAIN_MARGIN_MILLIS = 2_000L

        // Above the output latency of common Bluetooth earbuds, and below the echo filter's early margin of 1 s.
        const val MAX_LATENCY_MILLIS = 500L
    }
}
