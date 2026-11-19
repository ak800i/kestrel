package dev.kestrel.providers

import dev.kestrel.providers.catalog.CatalogFile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Spec 17's check that `providers.json` matches `openclaw.lock`, as a unit test that runs with `check` (design D2). */
class CatalogFilesTest {
    private val folder = File(System.getProperty("kestrel.catalog") ?: error("The build passes -Dkestrel.catalog"))
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun providersJsonNamesTheCommitOfTheLock() {
        val lock = folder.resolve("openclaw.lock").readText()
        val catalog = json.decodeFromString(CatalogFile.serializer(), folder.resolve("providers.json").readText())

        assertTrue("openclaw.lock must hold one 40-character hash and a line feed", Regex("^[0-9a-f]{40}\n$").matches(lock))
        assertEquals(lock.trim(), catalog.openclawCommit)
        assertTrue(catalog.providers.isNotEmpty())
    }
}
