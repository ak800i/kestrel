package dev.kestrel.voice

import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.ModelFiles
import dev.kestrel.voice.models.ModelManifest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class VoiceEnginesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun anEngineWhoseLoadWasCancelledIsClosed() = runBlocking {
        val models = ModelFiles(folder.newFolder("models"), ModelManifest(format = 1, models = emptyList()), File(folder.root, "hashes.json"))
        val engines = VoiceEngines(models, folder.newFolder("work"))
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
}
