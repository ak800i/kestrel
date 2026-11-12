package dev.kestrel.build.audit

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the dynamic symbols that a little-endian ELF shared library imports. */
object ElfReader {
    private const val SHT_DYNSYM = 11

    private data class Section(val type: Int, val offset: Long, val size: Long, val link: Int, val entrySize: Long)

    fun importedSymbols(bytes: ByteArray): Set<String> {
        require(
            bytes.size >= 64 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte(),
        ) { "Not an ELF file" }
        val is64 = when (bytes[4].toInt()) {
            1 -> false
            2 -> true
            else -> throw IllegalArgumentException("Unknown ELF class ${bytes[4]}")
        }
        require(bytes[5].toInt() == 1) { "Only little-endian ELF files are supported" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sectionTable = if (is64) buffer.getLong(0x28) else buffer.getInt(0x20).toUInt().toLong()
        val sectionHeaderSize = buffer.getShort(if (is64) 0x3A else 0x2E).toInt() and 0xFFFF
        val sectionCount = buffer.getShort(if (is64) 0x3C else 0x30).toInt() and 0xFFFF
        val sections = (0 until sectionCount).map { index ->
            val base = (sectionTable + index.toLong() * sectionHeaderSize).toInt()
            if (is64) {
                Section(
                    type = buffer.getInt(base + 0x04),
                    offset = buffer.getLong(base + 0x18),
                    size = buffer.getLong(base + 0x20),
                    link = buffer.getInt(base + 0x28),
                    entrySize = buffer.getLong(base + 0x38),
                )
            } else {
                Section(
                    type = buffer.getInt(base + 0x04),
                    offset = buffer.getInt(base + 0x10).toUInt().toLong(),
                    size = buffer.getInt(base + 0x14).toUInt().toLong(),
                    link = buffer.getInt(base + 0x18),
                    entrySize = buffer.getInt(base + 0x24).toUInt().toLong(),
                )
            }
        }
        // Without a dynamic symbol table, the imports cannot be checked, so the library must not pass silently.
        val symbols = sections.firstOrNull { it.type == SHT_DYNSYM }
            ?: throw IllegalArgumentException("No dynamic symbol table")
        val strings = sections[symbols.link]
        val entrySize = if (symbols.entrySize > 0) symbols.entrySize else if (is64) 24L else 16L
        val imported = linkedSetOf<String>()
        for (index in 0 until (symbols.size / entrySize).toInt()) {
            val base = (symbols.offset + index * entrySize).toInt()
            val nameOffset = buffer.getInt(base)
            val sectionIndex = buffer.getShort(base + if (is64) 6 else 14).toInt() and 0xFFFF
            if (nameOffset != 0 && sectionIndex == 0) {
                imported += readString(bytes, (strings.offset + nameOffset).toInt())
            }
        }
        return imported
    }

    private fun readString(bytes: ByteArray, start: Int): String {
        var end = start
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, start, end - start, Charsets.US_ASCII)
    }
}

/** Native functions that open network connections or resolve host names. */
object NativeLibraryRules {
    val NETWORK_SYMBOLS = setOf(
        "socket", "connect", "getaddrinfo", "gethostbyname", "gethostbyname2", "gethostbyname_r",
        "gethostbyname2_r", "android_getaddrinfofornetwork", "android_res_nquery", "res_query", "res_search",
    )
}
