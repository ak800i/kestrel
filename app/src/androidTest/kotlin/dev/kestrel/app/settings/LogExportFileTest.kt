package dev.kestrel.app.settings

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogExportFileTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph = (instrumentation.targetContext.applicationContext as KestrelApplication).graph
    private val resolver = instrumentation.targetContext.contentResolver

    // The provider belongs to the test APK and runs in its process, so the file is checked only through this URI.
    private val uri = Uri.parse("content://${instrumentation.context.packageName}.exportfile/export.json")

    @After
    fun deleteFile() {
        resolver.delete(uri, null, null)
    }

    @Test
    fun exportReplacesALongerExistingFile() = runBlocking {
        assertTrue(writeLogExport(resolver, uri, graph.logs))
        val size = read().length
        resolver.openOutputStream(uri, "wt")!!.use { it.write("x".repeat(size + 1000).toByteArray()) }

        assertTrue(writeLogExport(resolver, uri, graph.logs))

        val text = read()
        Json.parseToJsonElement(text)
        assertFalse("The file still holds the bytes from before", "x".repeat(1000) in text)
    }

    private fun read() = resolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
}
