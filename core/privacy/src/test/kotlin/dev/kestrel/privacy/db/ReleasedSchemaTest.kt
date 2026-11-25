package dev.kestrel.privacy.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A released version of Kestrel's database never changes (design 9.2 of sub-project 7): a phone that holds it opens it
 * only with the same schema, and the only way back would be a destructive fallback, which would delete the owner's
 * profile and settings. Room writes the current version's schema into `schemas` at each build; each version there has a
 * frozen copy in `src/test/resources/released-schemas`, which only the release of a new version adds. So a change of an
 * entity without a new version and its migration fails this test.
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
                released.getValue(name),
                schema,
            )
        }
    }

    private fun schemas(directory: String): Map<String, String> =
        File(directory).listFiles { file -> file.extension == "json" }.orEmpty().associate { it.name to it.readText().replace("\r\n", "\n") }

    private companion object {
        const val EXPORTED = "schemas/dev.kestrel.privacy.db.KestrelDatabase"
        const val RELEASED = "src/test/resources/released-schemas"
    }
}
