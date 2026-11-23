package dev.kestrel.voice.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** How far a download of the models is: [doneBytes] of the [totalBytes] that it fetches (release plan). */
data class DownloadProgress(val doneBytes: Long, val totalBytes: Long)

/**
 * Why a download of the models stopped (release plan). A `file` is a path of the manifest or an archive's id, which holds
 * nothing of the owner's, so the setup checklist names it.
 */
sealed interface DownloadFailure {
    /** The phone has no network. */
    data object NoNetwork : DownloadFailure

    /**
     * The network counts its data, such as mobile data, and the owner allowed only networks that do not. [wifi]: the
     * network is a Wi-Fi that Android rates as metered, such as a phone's hotspot.
     */
    data class MeteredNetwork(val wifi: Boolean = false) : DownloadFailure

    /** The download needs [neededBytes] of space, and the folder of the models has [freeBytes]. */
    data class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) : DownloadFailure

    /** A server answered with the HTTP [status], which is neither a success nor a redirect, or with too many redirects. */
    data class Server(val file: String, val status: Int) : DownloadFailure

    /** The network allowlist refused a host, such as a redirect to a host that the manifest does not name. */
    data class Refused(val file: String) : DownloadFailure

    /** The connection failed or broke. */
    data class Connection(val file: String) : DownloadFailure

    /** Kestrel could not write to the folder of the models. */
    data class Storage(val file: String) : DownloadFailure

    /** The file's size or SHA-256 hash differs from the manifest's, so Kestrel deleted it. */
    data class Damaged(val file: String) : DownloadFailure

    /** Any other failure, which the caller reports by its classes. */
    data object Unexpected : DownloadFailure
}

/** A download of the models that stopped for [failure]. Its message is only the failure's kind. */
class ModelDownloadException(val failure: DownloadFailure, cause: Throwable? = null) :
    IOException(failure::class.java.simpleName, cause)

/**
 * Downloads the model files that are missing or damaged under [files]' folder, from the addresses in [files]' manifest,
 * which comes with the app, through [calls], the network gateway's (design spec, section 8.10; release plan). Each file,
 * and each archive whose members Kestrel needs, first goes into a part file below the folder `.download`, which the next
 * download continues with an HTTP range. A file reaches its place only after its size and SHA-256 hash match the
 * manifest's; a part that does not match is deleted. Redirects are followed here, one request at a time, because the
 * gateway follows none, so that the allowlist checks each host. [decorate] marks each request for the allowlist and the
 * network log, and [refused] tells the allowlist's refusal from other failures.
 */
class ModelDownloader(
    private val files: ModelFiles,
    private val calls: Call.Factory,
    private val refused: (IOException) -> Boolean = { false },
    private val decorate: (Request.Builder) -> Request.Builder = { it },
) {
    /** The models that a download brings: every model that is not optional, and the Kokoro voice when [kokoro]. */
    fun wanted(kokoro: Boolean): List<Model> = files.manifest.models.filter { !it.optional || (kokoro && it.id == KOKORO_ID) }

    /**
     * Downloads every file of the [wanted] models that is missing or damaged. [gate] says before each file whether the
     * network may be used now, and [progress] hears the bytes fetched so far, also those of part files from earlier
     * downloads. Throws [ModelDownloadException]; a cancellation stops before the next 64 KB and keeps the part files.
     */
    suspend fun download(
        kokoro: Boolean,
        gate: () -> DownloadFailure? = { null },
        progress: (DownloadProgress) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        val wantedIds = wanted(kokoro).map { it.id }.toSet()
        val needed = files.check().filter { it.model.id in wantedIds }.flatMap { it.missing + it.damaged }.toSet()
        val entries = files.manifest.models.flatMap { it.files }.filter { it.path in needed }
        val root = files.root
        val parts = File(root, DOWNLOADS)
        val direct = entries.filter { it.url != null }
            .map { Download(it.path, it.url!!, it.size, it.sha256, File(parts, it.path + PART)) }
        val members = entries.filter { it.url == null }.groupBy { it.archive!! }
        val archives = files.manifest.archives.associateBy { it.id }
        val packed = members.keys.map { id ->
            val archive = archives.getValue(id)
            Download(id, archive.url, archive.size, archive.sha256, File(parts, id + PART))
        }
        val total = (direct + packed).sumOf { it.size }
        var done = (direct + packed).sumOf { it.part.length().coerceAtMost(it.size) }
        val needs = total - done + members.values.sumOf { list -> list.sumOf { it.size } }
        val free = root.apply { mkdirs() }.usableSpace
        if (free < needs) throw ModelDownloadException(DownloadFailure.NotEnoughSpace(needs, free))
        progress(DownloadProgress(done, total))
        val count: (Long) -> Unit = { bytes ->
            done += bytes
            progress(DownloadProgress(done, total))
        }
        for (download in direct) {
            fetch(download, gate, count)
            place(download.part, files.file(download.name), download.name)
        }
        for (download in packed) {
            fetch(download, gate, count)
            unpack(download, members.getValue(download.name), parts)
        }
    }

    /** Brings [download]'s part file to its full size and checks its hash, or throws. */
    private suspend fun fetch(download: Download, gate: () -> DownloadFailure?, count: (Long) -> Unit) {
        val part = download.part
        part.parentFile?.mkdirs()
        if (part.length() > download.size) {
            count(-download.size)
            part.delete()
        }
        if (part.length() < download.size) {
            gate()?.let { throw ModelDownloadException(it) }
            val start = part.length()
            open(download, start).use { response ->
                // A server that ignores the range sends the whole file again.
                val append = start > 0 && response.code == 206
                if (start > 0 && !append) count(-start)
                val output = try {
                    FileOutputStream(part, append)
                } catch (e: IOException) {
                    throw ModelDownloadException(DownloadFailure.Storage(download.name), e)
                }
                // The output stream has truncated a part that starts over, so the part's length is where this body begins.
                try {
                    output.use { copy(response.body.byteStream(), it, download.name, download.size - part.length(), count) }
                } catch (e: ModelDownloadException) {
                    if (e.failure is DownloadFailure.Damaged) part.delete()
                    throw e
                }
            }
        }
        if (part.length() != download.size || sha256(part) != download.sha256) {
            part.delete()
            throw ModelDownloadException(DownloadFailure.Damaged(download.name))
        }
    }

    /** The response with the file's bytes from [start] on, after at most [MAX_REDIRECTS] redirects. */
    private fun open(download: Download, start: Long): Response {
        var url = download.url.toHttpUrl()
        var status = 0
        repeat(MAX_REDIRECTS + 1) {
            val builder = Request.Builder().url(url)
            if (start > 0) builder.header("Range", "bytes=$start-")
            val response = try {
                calls.newCall(decorate(builder).build()).execute()
            } catch (e: IOException) {
                val failure = if (refused(e)) DownloadFailure.Refused(download.name) else DownloadFailure.Connection(download.name)
                throw ModelDownloadException(failure, e)
            }
            status = response.code
            if (status == 200 || status == 206) return response
            val location = response.header("Location")?.let { response.request.url.resolve(it) }
            response.close()
            if (status !in REDIRECTS || location == null) throw ModelDownloadException(DownloadFailure.Server(download.name, status))
            url = location
        }
        throw ModelDownloadException(DownloadFailure.Server(download.name, status))
    }

    /** Copies at most [limit] bytes; a longer body is not the manifest's file, and it would fill the storage. */
    private suspend fun copy(input: InputStream, output: OutputStream, name: String, limit: Long, count: (Long) -> Unit) {
        val buffer = ByteArray(BUFFER_BYTES)
        var left = limit
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = try {
                input.read(buffer)
            } catch (e: IOException) {
                throw ModelDownloadException(DownloadFailure.Connection(name), e)
            }
            if (read < 0) return
            if (read > left) throw ModelDownloadException(DownloadFailure.Damaged(name))
            left -= read
            try {
                output.write(buffer, 0, read)
            } catch (e: IOException) {
                throw ModelDownloadException(DownloadFailure.Storage(name), e)
            }
            count(read.toLong())
        }
    }

    /**
     * Unpacks [members] from [download]'s checked archive, each into a part file of its own, and moves each into place
     * after its hash matched. The archive's part file goes once every member is in place.
     */
    private suspend fun unpack(download: Download, members: List<ModelFile>, parts: File) {
        val byMember = members.associateBy { it.member!! }
        val placed = mutableSetOf<String>()
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(download.part.inputStream().buffered(), true)).use { tar ->
                while (placed.size < byMember.size) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: break
                    val member = entry.name.removePrefix("./")
                    val file = byMember[member] ?: continue
                    val temporary = File(parts, file.path + PART)
                    temporary.parentFile?.mkdirs()
                    FileOutputStream(temporary).use { output -> tar.copyTo(output, BUFFER_BYTES) }
                    if (temporary.length() != file.size || sha256(temporary) != file.sha256) {
                        temporary.delete()
                        throw ModelDownloadException(DownloadFailure.Damaged(file.path))
                    }
                    place(temporary, files.file(file.path), file.path)
                    placed += member
                }
            }
        } catch (e: ModelDownloadException) {
            throw e
        } catch (e: IOException) {
            throw ModelDownloadException(DownloadFailure.Storage(download.name), e)
        }
        if (placed.size < byMember.size) throw ModelDownloadException(DownloadFailure.Damaged(download.name))
        download.part.delete()
    }

    private fun place(source: File, target: File, name: String) {
        try {
            target.parentFile?.mkdirs()
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw ModelDownloadException(DownloadFailure.Storage(name), e)
        }
    }

    /** One download: [name] is a file's path or an archive's id, and [part] holds its bytes until they are checked. */
    private class Download(val name: String, val url: String, val size: Long, val sha256: String, val part: File)

    companion object {
        /** The id of the Kokoro voice's model in models/manifest.json, which a download brings only when chosen. */
        const val KOKORO_ID = "tts-kokoro"

        private const val DOWNLOADS = ".download"
        private const val PART = ".part"
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
