package dev.kestrel.app.debug

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import dev.kestrel.app.AppGraph
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.listen.BoostWordBudget
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandParser
import dev.kestrel.commands.Commands

/**
 * Hook H2 of plan 4D, voice-expect: the phone compares the next transcripts of Kestrel's voice session with the text of a
 * clip that the PC plays, so that an air check reads the result of its own clip and never of a stray utterance
 * (2026-11-20-a71-checks-4d-interfaces.md, section 4). The match runs with its tag, as a tagged `voice -Send heard` does, or with `noRun`
 * is only resolved; every other transcript while the expectation holds is dropped. With a corpus line's group (`group`),
 * its scene's labels (`labels`) and the corpus's contacts (`contacts`), a match with `noRun` is judged as voice-eval
 * judges the line, with CorpusVerdict (plan 4E). The answers hold counts, times on the phone's clock and kinds, never what
 * Kestrel heard.
 */
internal class ExpectHooks(private val graph: AppGraph) {
    fun run(command: String, intent: Intent): String = when (command) {
        "VOICE_EXPECT" -> if (intent.getBooleanExtra(EXTRA_RESULT, false)) result(tag(intent)) else expect(intent)
        else -> throw HookError("unknown command '$command'")
    }

    private fun tag(intent: Intent): String = intent.getStringExtra(EXTRA_TAG) ?: throw HookError("add --es tag <tag>")

    private fun expect(intent: Intent): String {
        val tag = tag(intent)
        if (!TAG.matches(tag)) throw HookError("add --es tag with 1 to 32 letters, digits, '-' and '_'")
        val text = intent.getStringExtra(EXTRA_TEXT) ?: throw HookError("add -Text with words")
        val seconds = intent.getIntExtra(EXTRA_SECONDS, DEFAULT_SECONDS)
        if (seconds !in 1..MAX_SECONDS) throw HookError("add --ei seconds with 1 to $MAX_SECONDS")
        val noRun = intent.getBooleanExtra(EXTRA_NO_RUN, false)
        if (!noRun && VoiceHooks.refused(text, Build.HARDWARE)) throw HookError(VoiceHooks.YES_CALL_REFUSED)
        // As voice-eval reads them: without contacts, the labels are the contacts too.
        val corpus = intent.getStringExtra(EXTRA_GROUP)?.let { group ->
            val labels = names(intent.getStringExtra(EXTRA_LABELS))
            CorpusLine(group, labels, intent.getStringExtra(EXTRA_CONTACTS)?.let { names(it) } ?: labels)
        }
        val listening = graph.listening
        if (!listening.sessionOpen.value) throw HookError("Kestrel's voice session is not open")
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val current = expectation
            if (current != null && current.waiting(now)) {
                throw HookError("an expectation waits; ask for its answer with -Result -Tag ${current.tag}")
            }
            if (current?.tag == tag || VoiceHooks.tags.tagged(tag) != null) {
                throw HookError("the tag $tag is in use; give each transcript a new tag")
            }
            val next = Expectation(tag, text, noRun, now + seconds * 1_000L)
            if (next.words == 0) throw HookError("add -Text with words")
            expectation = next
            line = corpus
            listening.transcripts = { event -> check(graph, event) }
        }
        return "expecting tag=$tag seconds=$seconds"
    }

    private fun result(tag: String): String {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val current = expectation?.takeIf { it.tag == tag } ?: throw HookError("no expectation has the tag $tag")
            if (current.waiting(now)) return "waiting tag=$tag"
            val match = current.match
            val run = !current.noRun
            val number = if (run) VoiceHooks.tags.tagged(tag)?.command ?: 0 else 0
            val (kind, outcome) = if (run) VoiceHooks.kindAndOutcome(graph, number) else (match?.kind ?: "none") to "none"
            val corpus = line
            val right = when {
                run -> "-"
                match != null -> match.right
                // As voice-eval answers for a clip in which the recogniser heard nothing (plan 4E).
                corpus != null -> CorpusVerdict.command(corpus.group, current.expected, "", corpus.labels, corpus.contacts)
                isCommand(current.expected) -> "0"
                else -> "-"
            }
            return "tag=$tag matched=${match != null} words=${current.words} errors=${current.errors} stray=${current.stray} " +
                "split=${current.split} speechEnded=${match?.speechEnded ?: 0} closed=${match?.closed ?: 0} " +
                "transcribed=${match?.transcribed ?: 0} run=$run command=$number kind=$kind outcome=$outcome right=$right"
        }
    }

    companion object {
        val COMMANDS = setOf("VOICE_EXPECT")

        private val lock = Any()

        // The newest expectation; its answer stays until the next one.
        private var expectation: Expectation? = null

        // The corpus line of the newest expectation, or null when voice-expect named no group (plan 4E).
        private var line: CorpusLine? = null

        private val TAG = Regex("[A-Za-z0-9_-]{1,32}")
        private const val DEFAULT_SECONDS = 15
        private const val MAX_SECONDS = 60
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_NO_RUN = "noRun"
        private const val EXTRA_GROUP = "group"
        private const val EXTRA_LABELS = "labels"
        private const val EXTRA_CONTACTS = "contacts"

        // Decides for each transcript of a voice session whether it goes on to the Orchestrator, on the session's thread.
        private fun check(graph: AppGraph, event: Event.Heard): Boolean {
            val now = SystemClock.elapsedRealtime()
            synchronized(lock) {
                val current = expectation ?: return true
                if (!current.holds(now)) return true
                val noRun = current.noRun
                val corpus = line
                val verdict: (String) -> Pair<String, String> =
                    { heard -> if (noRun) resolve(graph, current.expected, heard, corpus) else "none" to "-" }
                if (!current.offer(event.text, event.endedAt, event.closedAt, now, verdict)) return false
                if (noRun) return false
                try {
                    VoiceHooks.tag(graph, current.tag, event)
                } catch (e: HookError) {
                    // A tagged text with the same tag came meanwhile; the match runs untagged, and its answer says command=0.
                }
                // Process rule 10: a heard "yes, call" never reaches Kestrel through a hook on a phone, also as a match. Its tag
                // stays, with no command, so that voice-state -Tag answers role=none.
                if (VoiceHooks.refused(event.text, Build.HARDWARE)) {
                    VoiceHooks.tags.dropped(event)
                    return false
                }
                return true
            }
        }

        // The kind of command that [heard] parses to, and 1 when it leads to the same action as [expected] with the screen's
        // labels, 0 when not, - when [expected] is no instant command, as voice-eval's command= (design spec, section 16.4).
        // A corpus line is judged as voice-eval judges it, with its own group, labels and contacts (plan 4E).
        private fun resolve(graph: AppGraph, expected: String, heard: String, corpus: CorpusLine?): Pair<String, String> {
            val said = CommandParser.parse(heard)
            val kind = said?.javaClass?.simpleName ?: "none"
            if (corpus != null) return kind to CorpusVerdict.command(corpus.group, expected, heard, corpus.labels, corpus.contacts)
            val wanted = CommandParser.parse(expected)
            if (wanted == null || wanted is Command.Bare) return kind to "-"
            val labels = graph.screen.value?.snapshots?.latest()?.snapshot?.let { BoostWordBudget.screenNames(it) }.orEmpty()
            return kind to if (said != null && Commands.sameAction(wanted, said, labels)) "1" else "0"
        }

        private fun isCommand(text: String): Boolean = CommandParser.parse(text).let { it != null && it !is Command.Bare }

        // The names of an extra separated by ';', as voice-eval reads its labels and contacts.
        private fun names(text: String?): List<String> = text?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }

    /** A corpus line's group, the labels of its scene and the contacts, with which voice-expect judges a match (plan 4E). */
    private class CorpusLine(val group: String, val labels: List<String>, val contacts: List<String>)
}
