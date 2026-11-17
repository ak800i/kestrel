package dev.kestrel.voice

import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.Model
import dev.kestrel.voice.models.ModelFile
import dev.kestrel.voice.models.ModelFiles
import dev.kestrel.voice.models.ModelManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class VoiceEnginesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun engines(work: File = folder.newFolder("work")): VoiceEngines {
        val models = ModelFiles(folder.newFolder("models"), ModelManifest(format = 1, models = emptyList()), File(folder.root, "hashes.json"))
        return VoiceEngines(models, work)
    }

    /** A required voice whose file is there, and an optional voice whose file is missing. */
    private fun voices(): VoiceEngines {
        val root = folder.newFolder("voices")
        File(root, "piper/voice.onnx").apply {
            parentFile!!.mkdirs()
            writeText("abc")
        }
        fun file(path: String) = ModelFile(path, 3, ABC, url = "https://example.org/$path")
        val piper = Model("tts-piper-ljspeech", Engine.TTS, "Piper", "MIT", "https://example.org", listOf(file("piper/voice.onnx")))
        val kokoro = Model("tts-kokoro", Engine.TTS, "Kokoro", "Apache-2.0", "https://example.org", listOf(file("kokoro/model.onnx")), optional = true)
        val models = ModelFiles(root, ModelManifest(format = 1, models = listOf(piper, kokoro)), File(folder.root, "voice-hashes.json"))
        return VoiceEngines(models, folder.newFolder("voice-work"))
    }

    @Test
    fun anEngineLoadsWithoutAMissingOptionalModel() = runBlocking {
        var created = false

        voices().load(Engine.TTS) {
            created = true
            AutoCloseable {}
        }.close()

        assertTrue(created)
    }

    @Test
    fun aLoadThatNamesTheOptionalModelNeedsItsFiles() = runBlocking {
        val error = try {
            voices().load(Engine.TTS, setOf("tts-kokoro")) { AutoCloseable {} }
            null
        } catch (e: ModelsNotReadyException) {
            e
        }

        assertNotNull("The load did not fail", error)
        assertEquals(listOf("tts-kokoro"), error!!.statuses.map { it.model.id })
    }

    @Test
    fun anEngineWhoseLoadWasCancelledIsClosed() = runBlocking {
        val engines = engines()
        val loading = CompletableDeferred<Unit>()
        val loaded = CountDownLatch(1)
        val closed = AtomicBoolean()

        val job = launch(Dispatchers.Default) {
            engines.load(Engine.VAD) {
                loading.complete(Unit)
                // The load goes on after the cancellation, as a native load does.
                loaded.await()
                AutoCloseable { closed.set(true) }
            }
        }
        loading.await()
        job.cancel()
        loaded.countDown()
        job.join()

        assertTrue(closed.get())
    }

    @Test
    fun anExceptionFromCloseDoesNotHideTheCancellation() = runBlocking {
        val engines = engines()
        val loading = CompletableDeferred<Unit>()
        val loaded = CountDownLatch(1)
        val thrown = AtomicReference<Throwable>()

        val job = launch(Dispatchers.Default) {
            try {
                engines.load(Engine.VAD) {
                    loading.complete(Unit)
                    loaded.await()
                    AutoCloseable { throw IOException("close failed") }
                }
            } catch (e: Throwable) {
                thrown.set(e)
                throw e
            }
        }
        loading.await()
        job.cancel()
        loaded.countDown()
        job.join()

        val cancellation = thrown.get()
        assertTrue("$cancellation", cancellation is CancellationException)
        assertTrue(cancellation.suppressed.single() is IOException)
    }

    @Test
    fun aWorkFileReplacesTheTemporaryFilesThatAnEndedProcessLeftBehind() {
        val work = folder.newFolder("work")
        val leftovers = listOf("keywords.txt123.tmp", "keywords.txt-45.tmp").map { File(work, it).apply { writeText("torn") } }
        val others = listOf("parakeet-bpe.vocab678.tmp", "keywords.txt.bak").map { File(work, it).apply { writeText("other") } }

        val file = engines(work).writeWorkFile("keywords.txt", "words")

        assertEquals("words", file.readText())
        assertTrue(leftovers.none { it.exists() })
        assertTrue(others.all { it.exists() })
        assertEquals(setOf("keywords.txt", "parakeet-bpe.vocab678.tmp", "keywords.txt.bak"), work.list()!!.toSet())
    }

    private companion object {
        // The SHA-256 hash of "abc".
        const val ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}