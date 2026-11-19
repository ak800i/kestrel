package dev.kestrel.providers.credentials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialCodecTest {
    private val all = listOf(
        Credential.ApiKey("hidden4711-key"),
        Credential.OAuthToken("hidden4711-access", "hidden4711-refresh", 1_800_000_000_000, 1_799_999_000_000, "oaiapp_hidden4711", "https://auth.example.com/token", "global"),
        Credential.GitHubToken("gho_hidden4711", "hidden4711.ghe.com"),
        Credential.AwsAccessKeys("AKIAHIDDEN4711", "hidden4711-access-key", "hidden4711-session"),
        Credential.ServiceAccountKey("hidden4711@project.iam.gserviceaccount.com", "-----BEGIN PRIVATE KEY-----\nhidden4711\n", "hidden4711-id", "hidden4711-project"),
    )

    @Test
    fun everyKindSurvivesTheBytes() {
        for (credential in all) {
            assertEquals(credential, CredentialCodec.decode(CredentialCodec.encode(credential)))
        }
        assertEquals("""{"kind":"api-key","key":"k"}""", CredentialCodec.encode(Credential.ApiKey("k")).decodeToString())
    }

    @Test
    fun bytesThatHoldNoCredentialFailWithoutThemInTheMessage() {
        val error = assertThrows(IllegalArgumentException::class.java) { CredentialCodec.decode("""{"kind":"other","key":"hidden4711"}""".encodeToByteArray()) }

        assertEquals("Not a stored credential", error.message)
    }

    @Test
    fun noCredentialPrintsItsSecrets() {
        val printed = all.joinToString()

        assertFalse(printed, printed.contains("4711") || printed.contains("auth.example"))
    }

    @Test
    fun aKeyMustBePrintableAsciiWithoutSpaces() {
        assertEquals(true, ApiKeys.acceptable("  sk-test_ABC.123~  "))
        for (bad in listOf("", "   ", "sk test", "sk-tést", "sk-\u0000x", "sk\t1")) {
            assertEquals(bad, false, ApiKeys.acceptable(bad))
        }
    }
}
