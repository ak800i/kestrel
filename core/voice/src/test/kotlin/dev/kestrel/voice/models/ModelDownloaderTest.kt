package dev.kestrel.voice.models

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ModelDownloaderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val server = MockWebServer()

    // The gateway follows no redirects (design spec, section 13.1), so neither does this stand-in for it.
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private val model = ByteArray(200_000) { (it % 251).toByte() }
    private val kokoro = "kokoro".toByteArray()

    @Before
    fun startServer() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stopServer() {
        server.close()
    }

    private val root get() = File(temporary.root, "models")

    private fun url(path: String) = server.url(path).toString()

    private fun manifest(vad: ModelFile = ModelFile("vad/a.onnx", model.size.toLong(), sha256(model), url = url("/a"))) =
        ModelManifest(
            format = 1,
            models = listOf(
                Model("vad", Engine.VAD, "VAD", "MIT", "https://example.org", listOf(vad)),
                Model(
                    "tts-kokoro", Engine.TTS, "Kokoro", "Apache-2.0", "https://example.org",
                    listOf(ModelFile("tts-kokoro/voices.bin", kokoro.size.toLong(), sha256(kokoro), url = url("/k"))), optional = true,
                ),
                Model(
                    "tts-kokoro-int8", Engine.TTS, "Kokoro int8", "Apache-2.0", "https://example.org",
                    listOf(ModelFile("tts-kokoro-int8/model.int8.onnx", 1, "0".repeat(64), url = url("/i"))), optional = true,
                ),
            ),
        )

    private fun downloader(manifest: ModelManifest) =
        ModelDownloader(ModelFiles(root, manifest, File(temporary.root, "hashes.json")), client)

    private fun body(bytes: ByteArray) = MockResponse.Builder().body(Buffer().write(bytes)).build()

    @Test
    fun aDownloadBringsEveryModelThatIsNotOptionalAndKokoroOnlyWhenChosen() {
        val downloader = downloader(manifest())

        assertEquals(listOf("vad"), downloader.wanted(kokoro = false).map { it.id })
        assertEquals(listOf("vad", "tts-kokoro"), downloader.wanted(kokoro = true).map { it.id })
    }

    @Test
    fun downloadsTheMissingFilesAndChecksTheirHashes() = runTest {
        server.enqueue(body(model))
        server.enqueue(body(kokoro))
        val progress = mutableListOf<DownloadProgress>()

        downloader(manifest()).download(kokoro = true) { progress += it }

        assertArrayEquals(model, File(root, "vad/a.onnx").readBytes())
        assertArrayEquals(kokoro, File(root, "tts-kokoro/voices.bin").readBytes())
        assertEquals(listOf("/a", "/k"), List(2) { server.takeRequest().target })
        val total = (model.size + kokoro.size).toLong()
        assertEquals(DownloadProgress(total, total), progress.last())
        assertFalse(File(root, ".download").walk().any { it.isFile })
    }

    @Test
    fun skipsTheFilesThatAreReady() = runTest {
        File(root, "vad").mkdirs()
        File(root, "vad/a.onnx").writeBytes(model)

        downloader(manifest()).download(kokoro = false)

        assertEquals(0, server.requestCount)
    }

    @Test
    fun continuesAPartFileWithARangeAndFollowsARelativeRedirect() = runTest {
        File(root, ".download/vad").mkdirs()
        File(root, ".download/vad/a.onnx.part").writeBytes(model.copyOf(50_000))
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/mirror/a").build())
        server.enqueue(MockResponse.Builder().code(206).body(Buffer().write(model.copyOfRange(50_000, model.size))).build())

        downloader(manifest()).download(kokoro = false)

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/a" to "bytes=50000-", first.target to first.headers["Range"])
        assertEquals("/mirror/a" to "bytes=50000-", second.target to second.headers["Range"])
        assertArrayEquals(model, File(root, "vad/a.onnx").readBytes())
    }

    @Test
    fun deletesAFileWhoseHashDiffers() = runTest {
        server.enqueue(body(model.reversedArray()))

        val error = runCatching { downloader(manifest()).download(kokoro = false) }.exceptionOrNull()

        assertEquals(DownloadFailure.Damaged("vad/a.onnx"), (error as ModelDownloadException).failure)
        assertFalse(File(root, "vad/a.onnx").exists())
        assertFalse(File(root, ".download/vad/a.onnx.part").exists())
    }

    @Test
    fun unpacksOnlyTheNeededMembersOfAnArchive() = runTest {
        val tokens = "a 1\n".toByteArray()
        val archive = tarBz2(mapOf("pack/tokens.txt" to tokens, "pack/other.bin" to model))
        server.enqueue(body(archive))
        val manifest = ModelManifest(
            format = 1,
            archives = listOf(Archive("pack", url("/pack.tar.bz2"), archive.size.toLong(), sha256(archive))),
            models = listOf(
                Model(
                    "kws", Engine.KWS, "KWS", "Apache-2.0", "https://example.org",
                    listOf(ModelFile("kws/tokens.txt", tokens.size.toLong(), sha256(tokens), archive = "pack", member = "pack/tokens.txt")),
                ),
            ),
        )

        downloader(manifest).download(kokoro = false)

        assertArrayEquals(tokens, File(root, "kws/tokens.txt").readBytes())
        assertEquals(listOf("tokens.txt"), File(root, "kws").list()!!.toList())
        assertFalse(File(root, ".download/pack.part").exists())
    }

    @Test
    fun stopsBeforeAnyRequestWhenTheNetworkMayNotBeUsed() = runTest {
        val error = runCatching {
            downloader(manifest()).download(kokoro = false, gate = { DownloadFailure.MeteredNetwork() })
        }.exceptionOrNull()

        assertEquals(DownloadFailure.MeteredNetwork(), (error as ModelDownloadException).failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun stopsAtTheManifestsSizeWhenTheServerSendsMore() = runTest {
        server.enqueue(body(model + ByteArray(1_000_000)))
        val progress = mutableListOf<DownloadProgress>()

        val error = runCatching { downloader(manifest()).download(kokoro = false) { progress += it } }.exceptionOrNull()

        assertEquals(DownloadFailure.Damaged("vad/a.onnx"), (error as ModelDownloadException).failure)
        assertTrue(progress.all { it.doneBytes <= it.totalBytes })
        assertFalse(File(root, ".download/vad/a.onnx.part").exists())
        assertFalse(File(root, "vad/a.onnx").exists())
    }

    @Test
    fun aCancelledDownloadKeepsItsPartForTheNextOne() = runTest {
        val large = ByteArray(4_000_000) { (it % 13).toByte() }
        server.enqueue(body(large))
        val manifest = manifest(ModelFile("vad/a.onnx", large.size.toLong(), sha256(large), url = url("/a")))
        lateinit var download: Job
        download = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            downloader(manifest).download(kokoro = false) { if (it.doneBytes > 0) download.cancel() }
        }

        download.start()
        download.join()

        val part = File(root, ".download/vad/a.onnx.part")
        assertTrue(part.length() in 1 until large.size)
        assertFalse(File(root, "vad/a.onnx").exists())
    }

    @Test
    fun cancelStopsARequestThatWaitsForTheServerAtOnce() = runTest {
        server.enqueue(MockResponse.Builder().body(Buffer().write(model)).headersDelay(30, TimeUnit.SECONDS).build())
        val download = launch(Dispatchers.IO) { downloader(manifest()).download(kokoro = false) }
        server.takeRequest()

        assertEndsAtOnceWhenCancelled(download)
        assertFalse(File(root, "vad/a.onnx").exists())
    }

    @Test
    fun cancelStopsAReadThatWaitsForTheServerAtOnceAndKeepsThePart() = runTest {
        val large = ByteArray(4_000_000) { (it % 13).toByte() }
        // 100 000 bytes, then nothing for 30 s.
        server.enqueue(MockResponse.Builder().body(Buffer().write(large)).throttleBody(100_000, 30, TimeUnit.SECONDS).build())
        val manifest = manifest(ModelFile("vad/a.onnx", large.size.toLong(), sha256(large), url = url("/a")))
        val received = CountDownLatch(1)
        val download = launch(Dispatchers.IO) {
            downloader(manifest).download(kokoro = false) { if (it.doneBytes >= 100_000) received.countDown() }
        }
        assertTrue(received.await(10, TimeUnit.SECONDS))

        assertEndsAtOnceWhenCancelled(download)
        assertEquals(100_000L, File(root, ".download/vad/a.onnx.part").length())
    }

    // The test's client waits 10 s for a read, so a cancellation that waited for the read would take that long.
    private fun assertEndsAtOnceWhenCancelled(download: Job) = runBlocking {
        // Long enough for the download to block in the request or the read.
        Thread.sleep(300)
        val started = System.nanoTime()
        download.cancel()
        download.join()
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("The download ended $millis ms after the cancellation", millis < 2_000)
        assertTrue(download.isCancelled)
    }

    private fun tarBz2(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            for ((name, content) in entries) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = content.size.toLong() })
                tar.write(content)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
