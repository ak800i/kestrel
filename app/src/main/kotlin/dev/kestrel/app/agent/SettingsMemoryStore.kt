package dev.kestrel.app.agent

import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.memory.RouteNote
import dev.kestrel.agent.memory.Routine
import dev.kestrel.agent.port.MemoryStore
import dev.kestrel.app.SettingKeys
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.privacy.settings.SettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Kestrel's memory in its encrypted settings (design D22 and D23 of sub-project 5): the facts and aliases, the route
 * notes per app (at most 20, the oldest gone first), the routines, the counts of `finish` signatures and the names of
 * the contacts that the last tasks found (at most [MAX_FOUND], newest first, for the boost words of D32 item 44). Keys of
 * facts and routines match after `Normalizer`, so "The gate code" replaces "the gate code". The screenshot approvals are
 * plan 5C's: [approved] and [approve] read the app graph's functions at each call. One writer at a time.
 */
internal class SettingsMemoryStore(
    private val settings: SettingsStore,
    private val approved: suspend (packageName: String, providerId: String) -> Boolean,
    private val approve: suspend (packageName: String, providerId: String) -> Unit,
) : MemoryStore {
    private val writes = Mutex()

    override suspend fun facts(): List<Fact> = settings.get(SettingKeys.MEMORY_FACTS)

    override suspend fun saveFact(fact: Fact) = change(SettingKeys.MEMORY_FACTS) { facts -> facts.filterNot { same(it.key, fact.key) } + fact }

    override suspend fun deleteFact(key: String): Boolean {
        var found = false
        change(SettingKeys.MEMORY_FACTS) { facts -> facts.filterNot { same(it.key, key) }.also { found = it.size < facts.size } }
        return found
    }

    override suspend fun notes(packageName: String): List<RouteNote> = settings.get(SettingKeys.ROUTE_NOTES)[packageName].orEmpty()

    override suspend fun saveNotes(packageName: String, notes: List<RouteNote>) =
        change(SettingKeys.ROUTE_NOTES) { all -> if (notes.isEmpty()) all - packageName else all + (packageName to notes.takeLast(MAX_NOTES)) }

    override suspend fun addFoundContacts(names: List<String>) =
        change(SettingKeys.FOUND_CONTACTS) { found -> (names.reversed() + found).distinctBy { Normalizer.normalize(it) }.take(MAX_FOUND) }

    override suspend fun screenshotApproved(packageName: String, providerId: String): Boolean = approved(packageName, providerId)

    override suspend fun approveScreenshots(packageName: String, providerId: String) = approve(packageName, providerId)

    override suspend fun routines(): List<Routine> = settings.get(SettingKeys.ROUTINES)

    override suspend fun saveRoutine(routine: Routine) =
        change(SettingKeys.ROUTINES) { routines -> routines.filterNot { same(it.name, routine.name) } + routine }

    override suspend fun countSignature(signature: String): Int {
        var count = 0
        change(SettingKeys.ROUTINE_SIGNATURES) { counts ->
            count = (counts[signature] ?: 0) + 1
            counts + (signature to count)
        }
        return count
    }

    /** Every app's route notes, for the Memory page. */
    suspend fun allNotes(): Map<String, List<RouteNote>> = settings.get(SettingKeys.ROUTE_NOTES)

    suspend fun deleteNotes(packageName: String) = change(SettingKeys.ROUTE_NOTES) { it - packageName }

    /**
     * The Memory page's edit of route note [old] into [new], in one change under the lock, so that a task's note stored
     * meanwhile is kept (task-5b-16-review M2). The note is found by its path: its counts, which a task may have changed
     * meanwhile, stay, and only the path changes. When a task removed the note meanwhile, [new] is stored as a new note,
     * so the owner's edit is never dropped. Another note with the new path gives way to it.
     */
    suspend fun replaceNote(old: RouteNote, new: RouteNote) = change(SettingKeys.ROUTE_NOTES) { all ->
        val notes = all[old.packageName].orEmpty()
        val index = notes.indexOfFirst { it.path == old.path }
        val edited = if (index >= 0) notes[index].copy(path = new.path) else new
        val kept = notes.filterIndexed { at, note -> at == index || (note.path != old.path && note.path != new.path) }
        val result = if (index >= 0) kept.map { if (it.path == old.path) edited else it } else kept + edited
        all + (old.packageName to result.takeLast(MAX_NOTES))
    }

    /** Deletes route note [note], found by its path, in one change under the lock. */
    suspend fun deleteNote(note: RouteNote) = change(SettingKeys.ROUTE_NOTES) { all ->
        val notes = all[note.packageName].orEmpty().filter { it.path != note.path }
        if (notes.isEmpty()) all - note.packageName else all + (note.packageName to notes)
    }

    /**
     * The Memory page's edit of the routine called [oldName] into [routine]'s name and phrasings, in one change under the
     * lock (task-5b-16-review M2): the stored routine keeps its slots and steps and its place; another routine with the new
     * name gives way to it, as a save does. A routine that is gone meanwhile is stored as [routine].
     */
    suspend fun replaceRoutine(oldName: String, routine: Routine) = change(SettingKeys.ROUTINES) { routines ->
        val stored = routines.firstOrNull { same(it.name, oldName) }
        val edited = stored?.copy(name = routine.name, phrasings = routine.phrasings) ?: routine
        val kept = routines.filter { it === stored || (!same(it.name, oldName) && !same(it.name, routine.name)) }
        if (stored != null) kept.map { if (it === stored) edited else it } else kept + edited
    }

    suspend fun deleteRoutine(name: String): Boolean {
        var found = false
        change(SettingKeys.ROUTINES) { routines -> routines.filterNot { same(it.name, name) }.also { found = it.size < routines.size } }
        return found
    }

    suspend fun signatures(): Map<String, Int> = settings.get(SettingKeys.ROUTINE_SIGNATURES)

    suspend fun foundContacts(): List<String> = settings.get(SettingKeys.FOUND_CONTACTS)

    /** "Delete all memory" (spec 5.1 goal 4; design D22): the facts, notes, routines, signatures and found contacts. */
    suspend fun deleteAll() = writes.withLock {
        for (key in listOf(SettingKeys.MEMORY_FACTS, SettingKeys.ROUTE_NOTES, SettingKeys.ROUTINES, SettingKeys.ROUTINE_SIGNATURES, SettingKeys.FOUND_CONTACTS)) {
            reset(key)
        }
    }

    private suspend fun <T> reset(key: SettingKey<T>) = settings.set(key, key.default)

    private suspend fun <T> change(key: SettingKey<T>, update: (T) -> T) = writes.withLock { settings.set(key, update(settings.get(key))) }

    private fun same(a: String, b: String): Boolean = Normalizer.normalize(a) == Normalizer.normalize(b)

    override fun toString(): String = "SettingsMemoryStore"

    companion object {
        const val MAX_NOTES = 20
        const val MAX_FOUND = 20
    }
}
