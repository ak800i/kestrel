package dev.kestrel.build.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ElfReaderTest {
    private fun library(name: String): ByteArray =
        javaClass.getResourceAsStream("/elf/$name")!!.use { it.readBytes() }

    private fun rejectionOf(bytes: ByteArray): String? =
        assertThrows(IllegalArgumentException::class.java) { ElfReader.importedSymbols(bytes) }.message

    @Test
    fun readsImportsFrom64BitLibrary() {
        val imports = ElfReader.importedSymbols(library("libnet-arm64.so"))
        assertTrue(imports.containsAll(listOf("socket", "connect", "getaddrinfo")))
    }

    @Test
    fun readsImportsFrom32BitLibrary() {
        val imports = ElfReader.importedSymbols(library("libnet-arm32.so"))
        assertTrue(imports.containsAll(listOf("socket", "connect", "getaddrinfo")))
    }

    @Test
    fun doesNotReportExportedFunctions() {
        assertFalse(ElfReader.importedSymbols(library("libnet-arm64.so")).contains("kestrel_open"))
        assertFalse(ElfReader.importedSymbols(library("libnet-arm32.so")).contains("kestrel_open"))
    }

    @Test
    fun libraryWithoutNetworkingHasNoNetworkImports() {
        val imports = ElfReader.importedSymbols(library("libquiet-arm64.so"))
        assertTrue(imports.contains("strlen"))
        assertEquals(emptySet<String>(), imports.intersect(NativeLibraryRules.NETWORK_SYMBOLS))
    }

    @Test
    fun rejectsFilesThatAreNotElf() {
        assertEquals("Not an ELF file", rejectionOf(ByteArray(64)))
    }

    @Test
    fun rejectsUnknownClassesAndBigEndianFiles() {
        assertEquals("Unknown ELF class 3", rejectionOf(library("libquiet-arm64.so").also { it[4] = 3 }))
        assertEquals("Only little-endian ELF files are supported", rejectionOf(library("libquiet-arm64.so").also { it[5] = 2 }))
    }

    @Test
    fun rejectsLibrariesWithoutDynamicSymbolTable() {
        // Setting the section count to zero hides every section, as some packers do.
        val withoutSections = library("libquiet-arm64.so").also {
            it[0x3C] = 0
            it[0x3D] = 0
        }
        assertEquals("No dynamic symbol table", rejectionOf(withoutSections))
    }

    @Test
    fun rejectsTruncatedFiles() {
        assertEquals("Truncated or malformed ELF file", rejectionOf(library("libnet-arm64.so").copyOf(1024)))
    }
}
