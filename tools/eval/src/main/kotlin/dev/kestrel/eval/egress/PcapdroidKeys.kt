package dev.kestrel.eval.egress

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * PCAPdroid's API keys by device serial in `eval\local\pcapdroid-keys.json`, which git ignores (design D11 of
 * sub-project 6). A key goes only to the device's control command; nothing prints it, and [toString] names only the file.
 */
class PcapdroidKeys(private val file: Path) {
    fun get(serial: String): String? = read()[serial]

    fun put(serial: String, key: String) {
        Files.createDirectories(file.toAbsolutePath().parent)
        Files.writeString(file, Json.encodeToString(SERIALIZER, read() + (serial to key)) + "\n")
    }

    private fun read(): Map<String, String> = if (Files.isRegularFile(file)) Json.decodeFromString(SERIALIZER, Files.readString(file)) else emptyMap()

    override fun toString(): String = "PcapdroidKeys(${file.fileName})"

    private companion object {
        val SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
