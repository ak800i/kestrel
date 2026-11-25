package dev.kestrel.privacy.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A released version of Kestrel's database never changes (design 9.2 of sub-project 7): a phone that holds it opens it
 * only with the same schema, and the only way back would be a destructive fallback, which would delete the owner's
 * profile and settings. Room writes the current version's schema into `schemas` at each build; each version there has a
 * frozen copy in `src/test/resources/released-schemas`, which only the release of a new version adds. A version is the
 * same release when its identity hash, which a phone checks when it opens the database, and its entities are the same,
 * however Room formats the file. So a change of an entity without a new version and its migration fails this test.
 */
class ReleasedSchemaTest {
    @Test
    fun everyVersionOfTheSchemaIsReleasedAndUnchanged() {
        val exported = schemas(EXPORTED)
        val released = schemas(RELEASED)

        assertTrue("Room wrote no schema into $EXPORTED", exported.isNotEmpty())
        assertEquals("Every version in $EXPORTED has its frozen copy in $RELEASED", released.keys, exported.keys)
        for ((name, schema) in exported) {
            assertEquals(
                "Version ${name.removeSuffix(".json")} of the database is released and never changes: raise the version and add a migration",
                release(released.getValue(name)),
                release(schema),
            )
        }
    }

    // A newer Room may format the file differently; only the identity hash and the entities decide.
    @Test
    fun aSchemaThatOnlyLooksDifferentIsTheSameReleaseAndAnotherHashIsNot() {
        val frozen = File("$RELEASED/3.json").readText()
        val reformatted = Json.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(frozen))
        val rehashed = frozen.replace("ad1d40a91456cfd3a17c4bb86294f892", "00000000000000000000000000000000")

        assertNotEquals(frozen, reformatted)
        assertEquals(release(frozen), release(reformatted))
        assertNotEquals(release(frozen), release(rehashed))
    }

    /** What a version's schema releases: its identity hash and its entities. */
    private data class Release(val identityHash: String, val entities: JsonElement)

    private fun release(schema: String): Release {
        val database = Json.parseToJsonElement(schema).jsonObject.getValue("database").jsonObject
        return Release(database.getValue("identityHash").jsonPrimitive.content, database.getValue("entities"))
    }

    private fun schemas(directory: String): Map<String, String> =
        File(directory).listFiles { file -> file.extension == "json" }.orEmpty().associate { it.name to it.readText() }

    private companion object {
        const val EXPORTED = "schemas/dev.kestrel.privacy.db.KestrelDatabase"
        const val RELEASED = "src/test/resources/released-schemas"
    }
}
