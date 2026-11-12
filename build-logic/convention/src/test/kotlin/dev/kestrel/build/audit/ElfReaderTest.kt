package dev.kestrel.build.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElfReaderTest {
    private fun library(name: String): ByteArray =
        javaClass.getResourceAsStream("/elf/$name")!!.use { it.readBytes() }

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
    }

    @Test
    fun libraryWithoutNetworkingHasNoNetworkImports() {
        val imports = ElfReader.importedSymbols(library("libquiet-arm64.so"))
        assertTrue(imports.contains("strlen"))
        assertEquals(emptySet<String>(), imports.intersect(NativeLibraryRules.NETWORK_SYMBOLS))
    }

    @Test
    fun rejectsFilesThatAreNotElf() {
        val error = runCatching { ElfReader.importedSymbols(ByteArray(64)) }.exceptionOrNull()
        assertEquals("Not an ELF file", error?.message)
    }
}
