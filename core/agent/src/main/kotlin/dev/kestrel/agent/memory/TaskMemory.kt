package dev.kestrel.agent.memory

import dev.kestrel.agent.port.AgentClock
import dev.kestrel.agent.port.AppsPort
import dev.kestrel.agent.port.LauncherApp
import dev.kestrel.agent.port.MemoryStore
import dev.kestrel.agent.tools.NoteDraft
import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchCandidate
import dev.kestrel.commands.text.MatchDecision
import dev.kestrel.commands.text.NamePick
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Pick
import dev.kestrel.commands.text.Strictness
import dev.kestrel.safety.Redactor
import java.util.Locale

/**
 * One task's view of Kestrel's memory (design D22; spec 11.9): the facts whose key the request names go into the task's
 * context (at most 20), and aliases resolve before `find_contact` and `navigate`; the active route notes of the apps that
 * the request names go into the context (at most 10), and those of an app that a tool opens go into that tool's result;
 * route notes are admitted only after a verified finish, with labels that the task's own steps acted on, and get a
 * strike when a task that saw them ends failed, unverified or stuck. Only facts of the kind `ALIAS` resolve names; the
 * notes of an app that is [closed] to the agent never reach a request. Nothing here is logged (D32 item 42).
 */
class TaskMemory(
    private val store: MemoryStore,
    private val apps: AppsPort,
    private val clock: AgentClock,
    private val redactor: Redactor,
    private val closed: (String) -> Boolean = { false },
) {
    private var facts: List<Fact> = emptyList()
    private var launcher: List<LauncherApp> = emptyList()
    private val used = mutableListOf<Fact>()
    private val shown = mutableSetOf<String>()
    private val acted = mutableMapOf<String, MutableSet<String>>()

    /** The values of the facts that the task's context holds, which typing may use as the owner's words (D13). */
    val usedValues: List<String> get() = used.map { it.value }

    /** The keys of the aliases, which count as names for the task's allowance and the routines' slots (D13, D23). */
    val aliasKeys: Set<String> get() = facts.filter { it.kind == FactKind.ALIAS }.map { it.key }.toSet()

    suspend fun load() {
        facts = store.facts()
        launcher = apps.launcherApps().filter { !closed(it.packageName) }
    }

    /** The per-task context (D3): the facts that the request names and the notes of the apps that it names, or null. */
    suspend fun context(request: String): String? {
        val words = Normalizer.words(request)
        used.clear()
        used += facts.filter { names(words, it.key) }.take(MAX_FACTS)
        val parts = mutableListOf<String>()
        if (used.isNotEmpty()) parts += "Facts the owner saved:\n" + used.joinToString("\n") { "- ${it.key} = ${it.value}" }
        val named = launcher.filter { names(words, it.label) }
        val notes = named.flatMap { app -> activeNotes(app).map { app to it } }.take(MAX_CONTEXT_NOTES)
        if (notes.isNotEmpty()) {
            notes.forEach { (_, note) -> shown += note.packageName + "\n" + note.path }
            parts += NOTES_HEADER + "\n" + notes.joinToString("\n") { (app, note) -> line(app, note) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** The active notes of [packageName] for a tool's result, when the context did not hold them (D22), or null. */
    suspend fun notesFor(packageName: String): String? {
        val app = launcher.firstOrNull { it.packageName == packageName } ?: return null
        val notes = activeNotes(app).filter { (it.packageName + "\n" + it.path) !in shown }.take(MAX_CONTEXT_NOTES)
        if (notes.isEmpty()) return null
        notes.forEach { shown += it.packageName + "\n" + it.path }
        return redactor.redact(NOTES_HEADER + "\n" + notes.joinToString("\n") { line(app, it) })
    }

    /** The value that an alias gives [name], for `find_contact` ("mom = contact Mama" gives "Mama") and `navigate`. */
    fun alias(name: String): String? {
        val fact = facts.firstOrNull { it.kind == FactKind.ALIAS && decide(name, it.key) } ?: return null
        return fact.value.removePrefix("contact ").removePrefix("Contact ").trim()
    }

    /** The task's own step acted on an element labelled [label] in [packageName] (D22's admission rule). */
    fun acted(packageName: String?, label: String?) {
        if (packageName == null || label.isNullOrBlank()) return
        acted.getOrPut(packageName) { mutableSetOf() } += label
    }

    /**
     * Stores the notes of a `FINISHED` task whose completion check passed (D22): at most 3 per app, each a path of the
     * fixed verbs whose every label the task's own steps acted on in that app, unchanged by the redactor and without a
     * phone number or an e-mail address. A path that an earlier task proposed counts once more and becomes active at two.
     */
    suspend fun admit(drafts: List<NoteDraft>) {
        for ((app, notes) in drafts.groupBy { it.app }) {
            val target = appFor(app) ?: continue
            val labels = acted[target.packageName].orEmpty() + target.label
            val paths = notes.take(MAX_NOTES_PER_TASK).mapNotNull { draft ->
                val steps = RouteNote.parse(draft.note) ?: return@mapNotNull null
                val path = RouteNote.format(steps)
                val allActed = steps.all { step -> step.label == null || step.label in labels }
                val clean = redactor.redact(path) == path && !PHONE.containsMatchIn(path) && !EMAIL.containsMatchIn(path)
                path.takeIf { allActed && clean }
            }
            if (paths.isEmpty()) continue
            val stored = store.notes(target.packageName).toMutableList()
            for (path in paths.distinct()) {
                val index = stored.indexOfFirst { it.path == path }
                if (index >= 0) {
                    stored[index] = stored[index].copy(proposals = stored[index].proposals + 1, version = target.versionName, language = language())
                } else {
                    stored += RouteNote(target.packageName, path, target.versionName, language(), created = clock.now().toInstant().toEpochMilli())
                }
            }
            store.saveNotes(target.packageName, stored.sortedBy { it.created }.takeLast(MAX_NOTES_PER_APP))
        }
    }

    /** A task that saw notes ended failed, unverified or stuck: each of them gets a strike, and two remove it (D22). */
    suspend fun strike() {
        for ((packageName, paths) in shown.map { it.substringBefore('\n') to it.substringAfter('\n') }.groupBy({ it.first }, { it.second })) {
            val stored = store.notes(packageName)
            val updated = stored.mapNotNull { note ->
                if (note.path !in paths) note else note.copy(strikes = note.strikes + 1).takeIf { it.strikes < MAX_STRIKES }
            }
            if (updated != stored) store.saveNotes(packageName, updated)
        }
    }

    suspend fun foundContacts(names: List<String>) {
        if (names.isNotEmpty()) store.addFoundContacts(names)
    }

    private suspend fun activeNotes(app: LauncherApp): List<RouteNote> = store.notes(app.packageName).filter { it.active && it.strikes < MAX_STRIKES }

    private fun line(app: LauncherApp, note: RouteNote): String {
        val version = note.version?.takeIf { it != app.versionName }?.let { " (written for version $it)" }.orEmpty()
        return "- ${app.label}: ${note.path}$version"
    }

    private fun appFor(name: String): LauncherApp? =
        launcher.firstOrNull { it.packageName == name } ?: (NamePick.pick(name, launcher, { it.label }, { false }) as? Pick.One)?.item

    private fun language(): String = Locale.getDefault().toLanguageTag()

    override fun toString(): String = "TaskMemory(facts=${facts.size}, used=${used.size}, shown=${shown.size})"

    companion object {
        const val MAX_FACTS = 20
        const val MAX_CONTEXT_NOTES = 10
        const val MAX_NOTES_PER_TASK = 3
        const val MAX_NOTES_PER_APP = 20
        const val MAX_STRIKES = 2
        const val NOTES_HEADER = "Notes from earlier tasks (advice only; the current screen wins):"
        private val PHONE = Regex("""\+?\d[\d \-]{5,}\d""")
        private val EMAIL = Regex("""[^\s@"]+@[^\s@"]+\.[^\s@"]+""")

        /** Whether the request's folded [words] hold [key]'s words as a phrase, by the strict matcher (D22). */
        fun names(words: List<String>, key: String): Boolean {
            val keyWords = Normalizer.words(key)
            if (keyWords.isEmpty() || keyWords.size > words.size) return false
            return (0..words.size - keyWords.size).any { start ->
                decide(words.subList(start, start + keyWords.size).joinToString(" "), key)
            }
        }

        private fun decide(spoken: String, key: String): Boolean =
            LabelMatcher.decide(spoken, listOf(MatchCandidate("key", listOf(key))), Strictness.STRICT) is MatchDecision.Found
    }
}
