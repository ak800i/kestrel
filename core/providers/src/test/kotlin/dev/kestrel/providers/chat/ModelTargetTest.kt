package dev.kestrel.providers.chat

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.http.NoAuth
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelTargetTest {
    @Test
    fun aTargetPrintsNeitherItsBaseUrlNorItsModelId() {
        val target = ModelTarget(
            providerId = "google-vertex",
            style = RequestStyle.VERTEX,
            baseUrl = "https://aiplatform.googleapis.com/v1/projects/secret-project/locations/global".toHttpUrl(),
            model = ModelEntry(id = "arn:aws:bedrock:us-east-1:123456789012:x", name = "X"),
            auth = NoAuth,
            options = StyleOptions(wire = RequestStyle.ANTHROPIC),
        )

        val printed = target.toString()

        assertFalse(printed, printed.contains("secret-project") || printed.contains("123456789012"))
        assertEquals(
            "ModelTarget(provider=google-vertex, style=VERTEX, modelIdLength=40, options=StyleOptions(chatgptPlan=false, " +
                "stripThinking=false, sanitizeToolCallIds=false, messagesPath=/v1/messages, wire=ANTHROPIC, compat=null, stream=null))",
            printed,
        )
    }
}
