package dev.kestrel.voice.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

    /**
     * An optional model of which no file is on the phone: the owner did not copy it, which is no problem. Once one of its
     * files is there, the others count as missing again.
     */
    val absent: Boolean get() = model.optional && damaged.isEmpty() && missing.isNotEmpty() && missing.size == model.files.size
}

/**
 * Checks the model files under [root] against [manifest] (design spec, section 8.10). A file of the wrong size is
 * damaged without being read; otherwise its SHA-256 hash decides. [hashCache] remembers each file's hash together with
 * its size and modification time, so that a file is hashed again only after it changes. The cache lives in Kestrel's
 * private storage, because anyone with adb can change the files under [root]. [findRoot] finds the folder again at each
 * use, because the folder can change while Kestrel runs, for example when shared storage comes back after a while.
 */
class ModelFiles(
    private val findRoot: () -> File,
    val manifest: ModelManifest,
    private val hashCache: File,
    private val hash: (File) -> String = ::sha256,
) {
    /** Checks the files under [root], a folder that never changes. */
    constructor(root: File, manifest: ModelManifest, hashCache: File, hash: (File) -> String = ::sha256) :
        this({ root }, manifest, hashCache, hash)

    private val lock = Mutex()

    /**
     * The folder that holds the model files, found again at each use. Finding it touches the disk, so read it and
     * [file] off the main thread.
     */
    val root: File get() = findRoot()

    /** Where a path from the manifest lives on this phone. */
    fun file(path: String): File = File(root, path)

    /**
     * Checks every model, in the manifest's order. Hashing a large file takes seconds, so this runs on [Dispatchers.IO].
     * It uses one folder for the whole check, and creates it first, so that scripts\push-models.ps1 finds it also after
     * someone deleted it while Kestrel ran. A cancelled check stops before the next file, but it first adds the hashes
     * that it computed to the cache, so that the next check hashes only the files that this one did not reach. A check
     * that finds a file missing keeps its entry, so that a check while shared storage is missing does not empty the cache.
     */
    suspend fun check(): List<ModelStatus> = withContext(Dispatchers.IO) {
        lock.withLock {
            val folder = root.apply { mkdirs() }
            val cached = readCache()
            val known = mutableMapOf<String, CachedHash>()
            try {
                val statuses = manifest.models.map { model ->
                    val missing = mutableListOf<String>()
                    val damaged = mutableListOf<String>()
                    for (entry in model.files) {
                        ensureActive()
                        val file = File(folder, entry.path)
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
                // Each entry holds its file's size and modification time, so the old entries of the files that this
                // check did not find or did not reach stay valid, also while shared storage is missing for a while.
                val kept = cached + known
                if (kept != cached) writeCache(kept)
                statuses
            } catch (e: CancellationException) {
                // As above. writeCache does not suspend, so the cancellation cannot stop it.
                val kept = cached + known
                if (kept != cached) writeCache(kept)
                throw e
            }
        }
    }

    private fun hashOf(file: File, path: String, cached: Map<String, CachedHash>, known: MutableMap<String, CachedHash>): String {
        val size = file.length()
        val modified = file.lastModified()
        val fileHash = cached[path]?.takeIf { it.size == size && it.modified == modified }?.sha256 ?: hash(file)
        known[path] = CachedHash(size, modified, fileHash)
        return fileHash
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
