package dev.kestrel.app.debug

import dev.kestrel.voice.SAMPLE_RATE
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** The clips of the voice corpus: WAV files of 16 kHz mono 16-bit samples (design spec, section 16.4). */
internal object Wav {
    /** Writes [samples], between -1 and 1, into [file] through a temporary file, so that no half clip is ever read. */
    fun write(file: File, samples: FloatArray) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) data.putShort((sample.coerceIn(-1f, 1f) * 32_767).roundToInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data.capacity()).put("WAVE".toByteArray(Charsets.US_ASCII))
        // The format: PCM, one channel, 16 kHz, two bytes a sample.
        header.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
        header.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(data.capacity())
        val temporary = File(file.path + ".tmp")
        temporary.outputStream().use { output ->
            output.write(header.array())
            output.write(data.array())
        }
        file.delete()
        check(temporary.renameTo(file)) { "The clip could not be written" }
    }

    /**
     * Reads the samples of a WAV file of 16 kHz mono 16-bit PCM, between -1 and 1. Throws [IllegalArgumentException] for
     * another format or a damaged file.
     */
    fun read(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun name(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        require(bytes.size >= 12 && name(0) == "RIFF" && name(8) == "WAVE") { "Not a WAV file" }
        var position = 12
        var formatRead = false
        while (position + 8 <= bytes.size) {
            val size = buffer.getInt(position + 4)
            val body = position + 8
            require(size >= 0 && body + size <= bytes.size) { "A damaged WAV file" }
            when (name(position)) {
                "fmt " -> {
                    require(
                        size >= 16 && buffer.getShort(body).toInt() == 1 && buffer.getShort(body + 2).toInt() == 1 &&
                            buffer.getInt(body + 4) == SAMPLE_RATE && buffer.getShort(body + 14).toInt() == 16,
                    ) { "Not 16 kHz mono 16-bit PCM" }
                    formatRead = true
                }
                "data" -> {
                    require(formatRead) { "The samples come before the format" }
                    return FloatArray(size / 2) { buffer.getShort(body + it * 2) / 32_768f }
                }
            }
            // A chunk of an odd size is followed by a byte of padding.
            position = body + size + size % 2
        }
        throw IllegalArgumentException("No samples")
    }
}
