package dev.kestrel.app.agent

import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.memory.FactKind
import dev.kestrel.agent.memory.RouteNote
import dev.kestrel.agent.memory.Routine
import dev.kestrel.agent.memory.RoutineStep
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.db.SettingRow
import dev.kestrel.privacy.db.SettingsDao
import dev.kestrel.privacy.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class SettingsMemoryStoreTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val settings = SettingsStore(MemoryDao(), AesGcmFieldCipher { key }, dispatcher = Dispatchers.Unconfined)
    private val approvals = mutableListOf<Pair<String, String>>()
    private val memory = SettingsMemoryStore(settings, { p, id -> (p to id) in approvals }, { p, id -> approvals += p to id })

    @Test
    fun aFactWithTheSameKeyReplacesTheOldOneAndForgettingFindsItTheSameWay() = runTest {
        memory.saveFact(Fact("the gate code", "blue"))
        memory.saveFact(Fact("The Gate Code", "red", FactKind.FACT, created = 5))
        memory.saveFact(Fact("mom", "contact Mama", FactKind.ALIAS))

        assertEquals(listOf("red", "contact Mama"), memory.facts().map { it.value })
        assertTrue(memory.deleteFact("the gate code"))
        assertFalse(memory.deleteFact("the gate code"))
        assertEquals(listOf("mom"), memory.facts().map { it.key })
    }

    @Test
    fun theSignaturesCountAndTheFoundContactsKeepTheNewestTwenty() = runTest {
        assertEquals(1, memory.countSignature("open-viber"))
        assertEquals(2, memory.countSignature("open-viber"))
        memory.addFoundContacts((1..19).map { "Person $it" })
        memory.addFoundContacts(listOf("Ana", "Person 3", "Marko"))

        val found = memory.foundContacts()
        assertEquals(20, found.size)
        assertEquals(listOf("Marko", "Person 3", "Ana", "Person 19"), found.take(4))
    }

    @Test
    fun deleteAllEmptiesEveryStoreOfMemoryAndTheApprovalsAreTheGraphs() = runTest {
        memory.saveFact(Fact("the gate code", "blue"))
        memory.countSignature("open-viber")
        memory.addFoundContacts(listOf("Ana"))
        memory.approveScreenshots("org.mozilla.firefox", "github-copilot")

        memory.deleteAll()

        assertEquals(emptyList<Fact>(), memory.facts())
        assertEquals(emptyMap<String, Int>(), memory.signatures())
        assertEquals(emptyList<String>(), memory.foundContacts())
        assertTrue(memory.screenshotApproved("org.mozilla.firefox", "github-copilot"))
    }

    @Test
    fun anEditedNoteKeepsWhatATaskChangedMeanwhileAndATaskRemovingItDoesNotDropTheEdit() = runTest {
        val app = "org.telegram.messenger"
        val shown = RouteNote(app, "open \"Chats\"", proposals = 1)
        val other = RouteNote(app, "tap \"Search\"", proposals = 2)
        memory.saveNotes(app, listOf(shown, other))
        // A task confirms the note while the Memory page shows its old version.
        memory.saveNotes(app, listOf(shown.copy(proposals = 2), other))

        memory.replaceNote(shown, shown.copy(path = "open \"Chats\" → tap \"New chat\""))

        assertEquals(listOf(RouteNote(app, "open \"Chats\" → tap \"New chat\"", proposals = 2), other), memory.notes(app))

        // A task removed the note meanwhile: the owner's edit is stored as a new note.
        memory.saveNotes(app, listOf(other))
        memory.replaceNote(shown, shown.copy(path = "open \"Archive\""))
        assertEquals(listOf(other, shown.copy(path = "open \"Archive\"")), memory.notes(app))

        // A delete finds the note by its path, also after a task struck it.
        memory.saveNotes(app, listOf(other.copy(strikes = 1)))
        memory.deleteNote(other)
        assertEquals(emptyList<RouteNote>(), memory.notes(app))
    }

    @Test
    fun aRenamedRoutineKeepsItsStepsAndReplacesARoutineOfTheNewName() = runTest {
        val step = RoutineStep("open_app", arguments = null)
        memory.saveRoutine(Routine("morning", listOf("good morning"), emptyList(), listOf(step, step)))
        memory.saveRoutine(Routine("evening", listOf("good evening"), emptyList(), emptyList()))
        memory.saveRoutine(Routine("Morning news", listOf("news"), emptyList(), emptyList()))

        memory.replaceRoutine("Morning", Routine("morning news", listOf("read the news", "news please"), emptyList(), emptyList()))

        assertEquals(
            listOf(Routine("morning news", listOf("read the news", "news please"), emptyList(), listOf(step, step)), Routine("evening", listOf("good evening"), emptyList(), emptyList())),
            memory.routines(),
        )
    }

    private class MemoryDao : SettingsDao {
        val rows = MutableStateFlow<Map<String, SettingRow>>(emptyMap())

        override suspend fun get(name: String): SettingRow? = rows.value[name]

        override fun observe(name: String): Flow<SettingRow?> = rows.map { it[name] }

        override suspend fun put(row: SettingRow) {
            rows.value = rows.value + (row.name to row)
        }

        override suspend fun delete(name: String): Int = if (rows.value.containsKey(name)) 1.also { rows.value = rows.value - name } else 0
    }
}
