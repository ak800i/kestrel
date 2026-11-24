package dev.kestrel.eval.egress

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/**
 * The one download of :tools:eval (design D11 of sub-project 6), and its one network class, which `audit/rules.txt`
 * allows: a pinned release, kept in [get]'s folder only when its size and SHA-256 match, so that `adb install` never
 * gets another file. A file that is already there and matches is not downloaded again.
 */
class ReleaseDownload(private val fetch: (String) -> InputStream = ::httpGet) {
    fun get(release: Release, folder: Path): Path {
        val file = folder.resolve(release.file)
        if (Files.isRegularFile(file) && matches(file, release)) return file
        Files.createDirectories(folder)
        val part = folder.resolve(release.file + ".part")
        fetch(release.url).use { Files.copy(it, part, StandardCopyOption.REPLACE_EXISTING) }
        if (!matches(part, release)) {
            Files.deleteIfExists(part)
            throw IllegalStateException("The download of ${release.file} does not have its pinned size and SHA-256, so it was deleted.")
        }
        return Files.move(part, file, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        fun sha256(file: Path): String =
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun matches(file: Path, release: Release): Boolean = Files.size(file) == release.size && sha256(file) == release.sha256

        private fun httpGet(url: String): InputStream {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build()
            val response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                response.body().close()
                throw IllegalStateException("The download of $url answered ${response.statusCode()}.")
            }
            return response.body()
        }
    }
}
