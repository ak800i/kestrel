package dev.kestrel.app.download

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.logs.NetworkOutcome
import dev.kestrel.privacy.network.BlockedRequestException
import dev.kestrel.privacy.network.ModelDownloadTag
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.voice.models.Archive
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.Model
import dev.kestrel.voice.models.ModelDownloader
import dev.kestrel.voice.models.ModelFile
import dev.kestrel.voice.models.ModelFiles
import dev.kestrel.voice.models.ModelManifest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The model download on the phone (release plan): into Kestrel's external files folder, where the models live, but into
 * a folder of its own, through a network gateway of its own, so that the test changes none of Kestrel's models and logs.
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloadDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.getExternalFilesDir(null), "download-check")
    private val events = CopyOnWriteArrayList<NetworkEvent>()
    private val policy = NetworkPolicy()
    private val gateway = NetworkGateway(policy) { events += it }

    @After
    fun deleteTheFolder() {
        folder.deleteRecursively()
    }

    private fun files(manifest: ModelManifest) = ModelFiles(File(folder, "models"), manifest, File(folder, "hashes.json"))

    private fun downloader(files: ModelFiles) =
        ModelDownloader(files, gateway.callFactory, refused = { it is BlockedRequestException }) {
            it.tag(ModelDownloadTag::class.java, ModelDownloadTag)
        }

    @Test
    fun downloadsAFileAndUnpacksAnArchiveFromALocalServer() = runBlocking {
        val model = ByteArray(300_000) { (it % 251).toByte() }
        val tokens = "a 1\nb 2\n".toByteArray()
        val archive = tarBz2(mapOf("pack/tokens.txt" to tokens, "pack/README" to "not needed".toByteArray()))
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse.Builder().body(Buffer().write(model)).build())
            server.enqueue(MockResponse.Builder().body(Buffer().write(archive)).build())
            policy.setLocalServers(listOf("127.0.0.1"))
            fun url(path: String) = server.url(path).newBuilder().host("127.0.0.1").build().toString()
            val files = files(
                ModelManifest(
                    format = 1,
                    archives = listOf(Archive("pack", url("/pack.tar.bz2"), archive.size.toLong(), sha256(archive))),
                    models = listOf(
                        Model(
                            "asr-check", Engine.ASR, "Check", "MIT", "https://example.org",
                            listOf(
                                ModelFile("asr-check/model.onnx", model.size.toLong(), sha256(model), url = url("/model.onnx")),
                                ModelFile("asr-check/tokens.txt", tokens.size.toLong(), sha256(tokens), archive = "pack", member = "pack/tokens.txt"),
                            ),
                        ),
                    ),
                ),
            )

            downloader(files).download(kokoro = false)

            assertTrue(files.check().single().ready)
            assertEquals(listOf("model.onnx", "tokens.txt"), File(folder, "models/asr-check").list()!!.sorted())
            assertTrue(File(folder, "models/.download").walk().none { it.isFile })
            assertEquals(listOf(ModelDownloadTag.LOG_PATH, ModelDownloadTag.LOG_PATH), events.map { it.path })
            assertTrue(events.all { it.outcome == NetworkOutcome.COMPLETED })
        }
    }

    @Test
    fun downloadsSmallFilesFromEachModelHostThroughTheAllowlist() = runBlocking<Unit> {
        assumeTrue(
            "Runs only with kestrelModelDownload=true",
            InstrumentationRegistry.getArguments().getString("kestrelModelDownload") == "true",
        )
        val bundled = ModelManifest.fromAssets(context.assets)
        // About 35 MB: Silero VAD and the keyword model's archive from GitHub's releases, Parakeet's tokens and decoder from
        // Hugging Face (a relative redirect and one to its download servers), the OCR detector from ModelScope and the
        // Latin dictionary from PaddleOCR's repository on GitHub.
        val wanted = setOf(
            "vad-silero/silero_vad.onnx",
            "kws-gigaspeech/tokens.txt",
            "asr-parakeet-v3/tokens.txt",
            "asr-parakeet-v3/decoder.int8.onnx",
            "ocr-ppocrv5-det/ch_PP-OCRv5_det_mobile.onnx",
            "ocr-ppocrv5-latin/ppocrv5_latin_dict.txt",
        )
        val manifest = bundled.copy(
            models = bundled.models.mapNotNull { model ->
                model.files.filter { it.path in wanted }.takeIf { it.isNotEmpty() }?.let { model.copy(files = it, optional = false) }
            },
        )
        policy.setModelHosts(bundled.downloadHosts)
        val files = files(manifest)

        downloader(files).download(kokoro = false)

        assertTrue(files.check().all { it.ready })
        assertTrue(events.none { it.outcome == NetworkOutcome.BLOCKED })
        val hosts = events.map { it.host }.toSortedSet()
        assertTrue(hosts.containsAll(setOf("github.com", "huggingface.co", "www.modelscope.cn", "raw.githubusercontent.com")))
        // The hosts are the public servers of the manifest and their download servers, never anything of the owner's.
        Log.i(TAG, "model download hosts=$hosts requests=${events.size}")
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

    private companion object {
        const val TAG = "KestrelModels"
    }
}
