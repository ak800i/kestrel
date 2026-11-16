package dev.kestrel.voice.models

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    private fun manifest(vararg files: Pair<String, String>) = ModelManifest(format = 1, models = listOf(model("m", *files)))

    private fun model(id: String, vararg files: Pair<String, String>) = Model(
        id = id,
        engine = Engine.ASR,
        name = id.uppercase(),
        licence = "MIT",
        project = "https://example.org",
        files = files.map { (path, content) ->
            ModelFile(path, content.length.toLong(), hashOf(content), url = "https://example.org/$path")
        },
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
    fun theModelsComeInTheManifestsOrder() = runTest {
        write("b/b.onnx", "b")
        val manifest = ModelManifest(format = 1, models = listOf(model("z", "z/z.onnx" to "z"), model("b", "b/b.onnx" to "b")))

        val statuses = modelFiles(manifest).check()

        assertEquals(listOf("z", "b"), statuses.map { it.model.id })
        assertEquals(listOf(false, true), statuses.map { it.ready })
    }

    @Test
    fun aFileThatCannotBeReadIsDamaged() = runTest {
        write("m/a.onnx", "abc")
        val files = ModelFiles(root, manifest("m/a.onnx" to "abc"), cache) { throw IOException("unreadable") }

        assertEquals(listOf("m/a.onnx"), files.check().single().damaged)
    }

    @Test
    fun twoChecksAtOnceHashAFileOnce() = runTest {
        write("m/a.onnx", "abc")
        val hashes = AtomicInteger()
        val files = ModelFiles(root, manifest("m/a.onnx" to "abc"), cache) { file ->
            hashes.incrementAndGet()
            // Slow enough that the second check would hash the file too if the checks overlapped.
            Thread.sleep(200)
            sha256(file)
        }

        val statuses = listOf(async { files.check() }, async { files.check() }).awaitAll()

        assertTrue(statuses.all { it.single().ready })
        assertEquals(1, hashes.get())
    }

    @Test
    fun aCancelledCheckStopsBeforeTheNextFile() = runTest {
        write("m/a.onnx", "abc")
        write("m/b.onnx", "de")

        cancelWhileTheFirstFileIsHashed(manifest("m/a.onnx" to "abc", "m/b.onnx" to "de"))

        assertEquals(listOf("a.onnx"), hashed)
    }

    @Test
    fun aCancelledCheckKeepsTheHashesThatItComputed() = runTest {
        write("m/a.onnx", "abc")
        write("m/b.onnx", "de")
        val manifest = manifest("m/a.onnx" to "abc", "m/b.onnx" to "de")
        cancelWhileTheFirstFileIsHashed(manifest)
        hashed.clear()

        val second = modelFiles(manifest).check().single()

        assertTrue(second.ready)
        assertEquals(listOf("b.onnx"), hashed)
    }

    /** Starts a check of [manifest] and cancels it while the check hashes its first file. */
    private suspend fun TestScope.cancelWhileTheFirstFileIsHashed(manifest: ModelManifest) {
        val hashing = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val files = ModelFiles(root, manifest, cache) { file ->
            hashed += file.name
            hashing.complete(Unit)
            // Hashing a large file takes seconds; the check is cancelled meanwhile.
            release.await(10, TimeUnit.SECONDS)
            sha256(file)
        }

        val checking = launch { files.check() }
        hashing.await()
        checking.cancel()
        release.countDown()
        checking.join()
    }

    @Test
    fun theFolderIsFoundAgainAtEachCheck() = runTest {
        write("m/a.onnx", "abc")
        // While shared storage is missing, Kestrel falls back to a folder in its private storage.
        var current = File(folder.root, "fallback")
        val files = ModelFiles({ current }, manifest("m/a.onnx" to "abc"), cache)

        assertEquals(listOf("m/a.onnx"), files.check().single().missing)

        current = root
        assertTrue(files.check().single().ready)
        assertEquals(File(root, "m/a.onnx"), files.file("m/a.onnx"))
    }

    @Test
    fun aCheckCreatesTheFolder() = runTest {
        // Someone deleted the folder while Kestrel ran; scripts\push-models.ps1 waits for Kestrel to create it.
        root.deleteRecursively()

        modelFiles(manifest("m/a.onnx" to "abc")).check()

        assertTrue(root.isDirectory)
    }

    @Test
    fun theHashIsLowercaseHex() {
        val file = write("x", "abc")

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256(file))
    }
}
