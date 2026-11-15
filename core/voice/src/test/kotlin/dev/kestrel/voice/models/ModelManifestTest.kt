package dev.kestrel.voice.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the repository's `models/manifest.json`, which both the app and scripts\push-models.ps1 read. */
class ModelManifestTest {
    // Unknown fields fail here, so that a misspelt field name cannot hide a missing value.
    private val manifest = Json.decodeFromString(ModelManifest.serializer(), File("../../models/manifest.json").readText())
    private val files = manifest.models.flatMap { it.files }

    @Test
    fun everyEngineHasAModel() {
        assertEquals(1, manifest.format)
        assertEquals(Engine.entries.toSet(), manifest.models.map { it.engine }.toSet())
    }

    @Test
    fun modelsAndPathsAreUnique() {
        assertEquals(manifest.models.size, manifest.models.map { it.id }.toSet().size)
        assertEquals(files.size, files.map { it.path }.toSet().size)
    }

    @Test
    fun everyFileHasASafePathASizeAndAHash() {
        for (file in files) {
            assertTrue(file.path, SAFE_PATH.matches(file.path))
            assertFalse(file.path, file.path.split('/').any { it == "." || it == ".." })
            assertTrue(file.path, file.size > 0)
            assertTrue(file.path, HASH.matches(file.sha256))
        }
    }

    @Test
    fun everyFileHasOneSource() {
        val archives = manifest.archives.map { it.id }.toSet()
        for (file in files) {
            if (file.url != null) {
                assertTrue(file.path, file.url.startsWith("https://"))
                assertNull(file.path, file.archive)
                assertNull(file.path, file.member)
            } else {
                assertTrue(file.path, file.archive in archives)
                assertNotNull(file.path, file.member)
            }
        }
    }

    @Test
    fun everyArchiveIsUsedAndChecked() {
        val used = files.mapNotNull { it.archive }.toSet()
        for (archive in manifest.archives) {
            assertTrue(archive.id, archive.id in used)
            assertTrue(archive.id, archive.url.startsWith("https://"))
            assertTrue(archive.id, archive.size > 0)
            assertTrue(archive.id, HASH.matches(archive.sha256))
        }
    }

    @Test
    fun theAppIgnoresFieldsThatItDoesNotKnow() {
        val text = """{"format":1,"later":true,"models":[{"id":"m","engine":"vad","name":"M","licence":"MIT",""" +
            """"project":"https://example.org","files":[{"path":"m/a.onnx","size":1,"sha256":"${"0".repeat(64)}",""" +
            """"url":"https://example.org/a","later":1}]}]}"""

        assertEquals("m/a.onnx", ModelManifest.parse(text).models.single().files.single().path)
    }

    private companion object {
        val SAFE_PATH = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")
        val HASH = Regex("[0-9a-f]{64}")
    }
}
