package dev.kestrel.app.settings

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.db.LogDao
import dev.kestrel.privacy.db.LogEntryRow
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.LogExport
import dev.kestrel.privacy.logs.LogStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.KeyGenerator

/**
 * The export writes into a file that the test APK's own provider serves (design D32 item 47 of sub-project 5): it exports
 * a store of its own with one known entry, in a database in memory, never the phone's logs, which would then lie in a
 * file that any app could read.
 */
@RunWith(AndroidJUnit4::class)
class LogExportFileTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = instrumentation.targetContext.contentResolver
    private val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, KestrelDatabase::class.java).build()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val logs = LogStore(database.logDao(), AesGcmFieldCipher { key }, System::currentTimeMillis)

    // The provider belongs to the test APK and runs in its process, so the file is checked only through this URI.
    private val uri = Uri.parse("content://${instrumentation.context.packageName}.exportfile/export.json")

    @After
    fun deleteFile() {
        resolver.delete(uri, null, null)
        database.close()
    }

    @Test
    fun exportWritesTheStoresEntryAndReplacesALongerExistingFile() = runBlocking {
        logs.append(ActivityEvent(ActivityKind.NOTE, "the test's own entry"))
        assertTrue(writeLogExport(resolver, uri, logs))
        val size = read().length
        resolver.openOutputStream(uri, "wt")!!.use { it.write("x".repeat(size + 1000).toByteArray()) }

        assertTrue(writeLogExport(resolver, uri, logs))

        val text = read()
        val export = Json.decodeFromString(LogExport.serializer(), text)
        assertEquals(listOf("the test's own entry"), export.activity.map { it.event.text })
        assertFalse("The file still holds the bytes from before", "x".repeat(1000) in text)
    }

    @Test
    fun aFailedExportLeavesTheFileEmpty() = runBlocking {
        val dao = database.logDao()
        var reads = 0
        // The second page's read fails, after the first page has reached the file.
        val failing = object : LogDao by dao {
            override suspend fun oldestAfter(log: String, afterMillis: Long, afterId: Long, limit: Int): List<LogEntryRow> {
                if (reads++ > 0) throw IllegalStateException("The test's failure")
                return dao.oldestAfter(log, afterMillis, afterId, limit)
            }
        }
        val failingLogs = LogStore(failing, AesGcmFieldCipher { key }, System::currentTimeMillis)
        repeat(LogStore.EXPORT_PAGE + 1) { failingLogs.append(ActivityEvent(ActivityKind.NOTE, "the test's entry $it " + "x".repeat(200))) }

        val failure = runCatching { writeLogExport(resolver, uri, failingLogs) }.exceptionOrNull()

        assertEquals("The test's failure", failure?.message)
        // Its length only, so that a failure never prints the file.
        assertEquals(0, read().length)
    }

    private fun read() = resolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
}
