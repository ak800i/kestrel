package dev.kestrel.voice.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Whether a model's files are on the phone and intact. [missing] and [damaged] hold paths from the manifest. */
data class ModelStatus(val model: Model, val missing: List<String>, val damaged: List<String>) {
    val ready: Boolean get() = missing.isEmpty() && damaged.isEmpty()
}

/**
 * Checks the model files under [root] against [manifest] (design spec, section 8.10). A file of the wrong size is
 * damaged without being read; otherwise its SHA-256 hash decides. [hashCache] remembers each file's hash together with
 * its size and modification time, so that a file is hashed again only after it changes. The cache lives in Kestrel's
 * private storage, because anyone with adb can change the files under [root].
 */
class ModelFiles(
    val root: File,
    val manifest: ModelManifest,
    private val hashCache: File,
    private val hash: (File) -> String = ::sha256,
) {
    private val lock = Mutex()

    /** Where a path from the manifest lives on this phone. */
    fun file(path: String): File = File(root, path)

    /** Checks every model, in the manifest's order. Hashing a large file takes seconds, so this runs on [Dispatchers.IO]. */
    suspend fun check(): List<ModelStatus> = withContext(Dispatchers.IO) {
        lock.withLock {
            val cached = readCache()
            val known = mutableMapOf<String, CachedHash>()
            val statuses = manifest.models.map { model ->
                val missing = mutableListOf<String>()
                val damaged = mutableListOf<String>()
                for (entry in model.files) {
                    val file = file(entry.path)
                    when {
                        !file.isFile -> missing += entry.path
                        file.length() != entry.size -> damaged += entry.path
                        else -> {
                            val actual = try {
                                hashOf(file, entry.path, cached, known)
                            } catch (e: IOException) {
                                null
                            }
                            if (actual != entry.sha256) damaged += entry.path
                        }
                    }
                }
                ModelStatus(model, missing, damaged)
            }
            if (known != cached) writeCache(known)
            statuses
        }
    }

    private fun hashOf(file: File, path: String, cached: Map<String, CachedHash>, known: MutableMap<String, CachedHash>): String {
        val size = file.length()
        val modified = file.lastModified()
        val hash = cached[path]?.takeIf { it.size == size && it.modified == modified }?.sha256 ?: hash(file)
        known[path] = CachedHash(size, modified, hash)
        return hash
    }

    private fun readCache(): Map<String, CachedHash> = try {
        if (hashCache.isFile) json.decodeFromString(CACHE, hashCache.readText()) else emptyMap()
    } catch (e: IOException) {
        emptyMap()
    } catch (e: IllegalArgumentException) {
        // A cache that cannot be read costs only one more hashing.
        emptyMap()
    }

    private fun writeCache(entries: Map<String, CachedHash>) {
        try {
            hashCache.parentFile?.mkdirs()
            val temporary = File(hashCache.path + ".tmp")
            temporary.writeText(json.encodeToString(CACHE, entries))
            Files.move(temporary.toPath(), hashCache.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // The next check hashes the files again.
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val CACHE = MapSerializer(String.serializer(), CachedHash.serializer())
    }
}

@Serializable
private data class CachedHash(val size: Long, val modified: Long, val sha256: String)

/** The SHA-256 hash of [file], in lowercase hex. */
fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
