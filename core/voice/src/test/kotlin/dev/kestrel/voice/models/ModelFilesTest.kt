package dev.kestrel.voice.models

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ModelFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var cache: File
    private val hashed = mutableListOf<String>()

    @Before
    fun setUp() {
        root = folder.newFolder("models")
        cache = File(folder.root, "private/model-hashes.json")
    }

    private fun manifest(vararg files: Pair<String, String>) = ModelManifest(
        format = 1,
        models = listOf(
            Model(
                id = "m",
                engine = Engine.ASR,
                name = "M",
                licence = "MIT",
                project = "https://example.org",
                files = files.map { (path, content) ->
                    ModelFile(path, content.length.toLong(), hashOf(content), url = "https://example.org/$path")
                },
            ),
        ),
    )

    private fun modelFiles(manifest: ModelManifest) = ModelFiles(root, manifest, cache) { file ->
        hashed += file.name
        sha256(file)
    }

    private fun write(path: String, content: String) = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(content)
    }

    private fun hashOf(content: String) =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun aMissingFileIsMissing() = runTest {
        val status = modelFiles(manifest("m/a.onnx" to "abc")).check().single()

        assertEquals(listOf("m/a.onnx"), status.missing)
        assertFalse(status.ready)
    }

    @Test
    fun aFileOfTheWrongSizeIsDamagedWithoutBeingRead() = runTest {
        write("m/a.onnx", "abcd")

        val status = modelFiles(manifest("m/a.onnx" to "abc")).check().single()

        assertEquals(listOf("m/a.onnx"), status.damaged)
        assertEquals(emptyList<String>(), hashed)
    }

    @Test
    fun aFileWithOtherContentIsDamaged() = runTest {
        write("m/a.onnx", "abd")

        assertEquals(listOf("m/a.onnx"), modelFiles(manifest("m/a.onnx" to "abc")).check().single().damaged)
    }

    @Test
    fun aModelWithAllItsFilesIsReady() = runTest {
        write("m/a.onnx", "abc")
        write("m/data/b", "de")

        val status = modelFiles(manifest("m/a.onnx" to "abc", "m/data/b" to "de")).check().single()

        assertTrue(status.ready)
        assertTrue(cache.isFile)
    }

    @Test
    fun anUnchangedFileIsHashedOnlyOnce() = runTest {
        write("m/a.onnx", "abc")
        val manifest = manifest("m/a.onnx" to "abc")

        modelFiles(manifest).check()
        val second = modelFiles(manifest).check().single()

        assertTrue(second.ready)
        assertEquals(listOf("a.onnx"), hashed)
    }

    @Test
    fun aFileWithANewModificationTimeIsHashedAgain() = runTest {
        val file = write("m/a.onnx", "abc")
        val manifest = manifest("m/a.onnx" to "abc")
        modelFiles(manifest).check()

        file.setLastModified(file.lastModified() - 60_000)
        modelFiles(manifest).check()

        assertEquals(listOf("a.onnx", "a.onnx"), hashed)
    }

    @Test
    fun aBrokenCacheIsIgnored() = runTest {
        write("m/a.onnx", "abc")
        cache.parentFile!!.mkdirs()
        cache.writeText("not json")

        assertTrue(modelFiles(manifest("m/a.onnx" to "abc")).check().single().ready)
    }

    @Test
    fun theHashIsLowercaseHex() {
        val file = write("x", "abc")

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256(file))
    }
}
