package dev.kestrel.catalogsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogSyncTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val commit = "e6113549fe8800b4c736c5e8a131b47f2e015143"
    private val checkout = File(javaClass.getResource("/openclaw/extensions")!!.toURI()).parentFile

    @Test
    fun theFixtureManifestsGiveTheSnapshot() {
        val expected = javaClass.getResource("/expected/providers.json")!!.readText()

        assertEquals(expected, CatalogSync.generate(checkout, commit))
    }

    @Test
    fun theSummaryCountsWhatTheSyncRead() {
        assertEquals(
            listOf(
                "Manifests read: 7",
                "Models plugins: 6",
                "Providers: 6",
                "Models: 7",
                "Providers per style: ANTHROPIC 1, CHAT_COMPLETIONS 2, GEMINI 1, OLLAMA 1, none 1",
                "Models whose all-zero cost became no price: 2",
                "Skipped: 4",
            ),
            CatalogSync.sync(checkout, commit).summary,
        )
    }

    @Test
    fun providerEndpointsAreNotRead() {
        val json = CatalogSync.generate(checkout, commit)

        assertFalse(json.contains("aiplatform"))
        assertFalse(json.contains("endpointClass"))
        assertTrue(json.contains("\"generativelanguage.googleapis.com\""))
    }

    @Test
    fun aManifestThatDoesNotParseStopsTheSyncWithItsPath() {
        val broken = temporary.newFolder("openclaw", "extensions", "broken")
        File(broken, "openclaw.plugin.json").writeText("""{"id": "broken", "categories": ["models"] "providers": []}""")

        val error = assertThrows(IllegalStateException::class.java) { CatalogSync.generate(broken.parentFile.parentFile, commit) }

        assertEquals("Cannot parse extensions/broken/openclaw.plugin.json", error.message)
    }

    @Test
    fun twoManifestsThatDefineOneProviderStopTheSync() {
        val groq = File(checkout, "extensions/groq/openclaw.plugin.json")
        for (folder in listOf("groq", "groq-copy")) {
            groq.copyTo(File(temporary.newFolder("openclaw", "extensions", folder), "openclaw.plugin.json"))
        }

        val error = assertThrows(IllegalStateException::class.java) { CatalogSync.generate(temporary.root.resolve("openclaw"), commit) }

        assertEquals("Two manifests define the provider groq", error.message)
    }

    @Test
    fun aShortCommitOrAFolderWithoutManifestsIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { CatalogSync.generate(checkout, "e6113549") }
        assertThrows(IllegalArgumentException::class.java) { CatalogSync.generate(temporary.newFolder("empty"), commit) }
    }
}
