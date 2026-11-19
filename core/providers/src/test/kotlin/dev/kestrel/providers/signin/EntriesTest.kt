package dev.kestrel.providers.signin

import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.signin.SignInFixtures.catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntriesTest {
    @Test
    fun aPastedKeyIsTrimmedAndRefusedWithACharacterThatOkHttpCannotSend() {
        assertEquals(Credential.ApiKey("sk-test-a1b2"), Entries.apiKey("  sk-test-a1b2\n"))
        assertNull(Entries.apiKey("sk-test a1b2"))
        assertNull(Entries.apiKey("sk-test-ä1b2"))
        assertNull(Entries.apiKey("sk-test-\u0007"))
        assertNull(Entries.apiKey("   "))
        assertEquals("…a1b2", Entries.masked("sk-test-a1b2"))
    }

    @Test
    fun aCustomBaseUrlMustBeHttpsWithoutUserQueryOrFragment() {
        assertEquals("https://gateway.example.com/v1", Entries.customBaseUrl(" https://gateway.example.com/v1 ").toString())
        assertNull(Entries.customBaseUrl("http://gateway.example.com/v1"))
        assertNull(Entries.customBaseUrl("https://user:secret@gateway.example.com/v1"))
        assertNull(Entries.customBaseUrl("https://gateway.example.com/v1?key=a"))
        assertNull(Entries.customBaseUrl("https://gateway.example.com/v1#top"))
        assertNull(Entries.customBaseUrl("gateway.example.com"))
    }

    @Test
    fun aServerAddressMayUsePlainHttpOnlyOnALocalHost() {
        assertEquals("http://192.168.1.20:11434/", Entries.serverAddress("http://192.168.1.20:11434").toString())
        assertEquals("http://127.0.0.1:1234/v1", Entries.serverAddress("http://127.0.0.1:1234/v1").toString())
        assertEquals("https://litellm.example.com/v1", Entries.serverAddress("https://litellm.example.com/v1").toString())
        assertNull(Entries.serverAddress("http://litellm.example.com/v1"))
        assertNull(Entries.serverAddress("http://192.168.1.20:11434/?token=a"))
        assertEquals(true, Entries.unencrypted(Entries.serverAddress("http://192.168.1.20:11434")!!))
    }

    @Test
    fun aServiceAccountFileKeepsOnlyItsFourFields() {
        val file = """
            {"type":"service_account","project_id":"test-project","private_key_id":"test-key-id",
             "private_key":"-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----\n",
             "client_email":"test@test-project.iam.gserviceaccount.com","client_id":"1","token_uri":"https://oauth2.googleapis.com/token"}
        """.trimIndent()

        assertEquals(
            Credential.ServiceAccountKey(
                "test@test-project.iam.gserviceaccount.com",
                "-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----\n",
                "test-key-id",
                "test-project",
            ),
            Entries.serviceAccount(file),
        )
        assertNull(Entries.serviceAccount(file.replace("service_account", "authorized_user")))
        assertNull(Entries.serviceAccount(file.replace("BEGIN PRIVATE KEY", "BEGIN RSA KEY")))
        assertNull(Entries.serviceAccount("not json"))
        assertNull(Entries.serviceAccount(file.replace("\"project_id\":\"test-project\"", "\"project_id\":\"Test/Project\"")))
    }

    @Test
    fun awsKeysTakeAnOptionalSessionToken() {
        assertEquals(Credential.AwsAccessKeys("AKIATEST", "test-secret", null), Entries.awsKeys(" AKIATEST ", "test-secret", " "))
        assertEquals(Credential.AwsAccessKeys("AKIATEST", "test-secret", "test-session"), Entries.awsKeys("AKIATEST", "test-secret", "test-session"))
        assertNull(Entries.awsKeys("AKIA TEST", "test-secret", null))
    }

    @Test
    fun eachProviderIsCheckedByItsListItsTokenOrOneShortRequest() {
        fun method(provider: String, choice: String) = Entries.checkMethod(catalog.provider(provider)!!, catalog.choice(provider, choice)?.settings)

        assertEquals(CheckMethod.DISCOVERY, method("groq", "groq-api-key"))
        assertEquals(CheckMethod.DISCOVERY, method("ollama", "ollama-server"))
        assertEquals(CheckMethod.DISCOVERY, method("github-copilot", "github-copilot"))
        assertEquals(CheckMethod.TOKEN_EXCHANGE, method("google-vertex", "vertex-service-account"))
        assertEquals(CheckMethod.SHORT_REQUEST, method("microsoft-foundry", "microsoft-foundry-apikey"))
        assertEquals(CheckMethod.SHORT_REQUEST, method("synthetic", "synthetic-api-key"))
        val request = Entries.checkRequest()
        assertEquals(16, request.maxOutputTokens)
        assertEquals(listOf(ChatMessage.User(listOf(Part.Text("Reply with OK.")))), request.messages)
    }
}
