package dev.kestrel.voice

import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.ModelFiles
import dev.kestrel.voice.models.ModelManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}