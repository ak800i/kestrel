package dev.kestrel.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReleaseSigningTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun withoutTheVariablesTheReleaseBuildStaysUnsigned() {
        assertNull(ReleaseSigning.from { null })
        assertNull(ReleaseSigning.from { "" })
    }

    @Test
    fun theFourVariablesNameTheKeyAndItsTextHidesThePasswords() {
        val keystore = folder.newFile("release.p12")
        val environment = mapOf(
            "KESTREL_KEYSTORE" to keystore.path,
            "KESTREL_KEYSTORE_PASSWORD" to "store-secret",
            "KESTREL_KEY_ALIAS" to "kestrel",
            "KESTREL_KEY_PASSWORD" to "key-secret",
        )

        val signing = ReleaseSigning.from(environment::get)

        assertEquals(ReleaseSigning(keystore, "store-secret", "kestrel", "key-secret"), signing)
        assertFalse(signing.toString().contains("secret"))
    }

    @Test
    fun someVariablesWithoutTheOthersFailWithTheirNamesOnly() {
        val error = runCatching { ReleaseSigning.from(mapOf("KESTREL_KEYSTORE_PASSWORD" to "store-secret")::get) }.exceptionOrNull()

        assertEquals(
            "Set all four release-signing variables or none; missing: KESTREL_KEYSTORE, KESTREL_KEY_ALIAS, KESTREL_KEY_PASSWORD",
            error?.message,
        )
    }

    @Test
    fun aKeystoreThatIsNoFileFails() {
        val environment = mapOf(
            "KESTREL_KEYSTORE" to folder.root.resolve("missing.p12").path,
            "KESTREL_KEYSTORE_PASSWORD" to "store-secret",
            "KESTREL_KEY_ALIAS" to "kestrel",
            "KESTREL_KEY_PASSWORD" to "key-secret",
        )

        val error = runCatching { ReleaseSigning.from(environment::get) }.exceptionOrNull()

        assertEquals("KESTREL_KEYSTORE names no file", error?.message)
    }
}
