package dev.kestrel.providers.styles

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VertexUrlsTest {
    @Test
    fun theLocationDecidesTheHostAndTheLocationAndTheProjectMustBePlainNames() {
        assertEquals("https://aiplatform.googleapis.com/v1/projects/my-project/locations/global".toHttpUrl(), VertexUrls.base("global", "my-project"))
        assertEquals("https://aiplatform.us.rep.googleapis.com/v1/projects/my-project/locations/us".toHttpUrl(), VertexUrls.base("us", "my-project"))
        assertEquals("https://aiplatform.eu.rep.googleapis.com/v1/projects/my-project/locations/eu".toHttpUrl(), VertexUrls.base("eu", "my-project"))
        assertEquals("https://us-east5-aiplatform.googleapis.com/v1/projects/my-project/locations/us-east5".toHttpUrl(), VertexUrls.base("us-east5", "my-project"))
        assertFalse(VertexUrls.validLocation("us-east5.evil.example"))
        assertFalse(VertexUrls.validLocation("US"))
        assertTrue(runCatching { VertexUrls.base("evil.example/x", "my-project") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(VertexUrls.validProject("example.com:my-project"))
        for (project in listOf("", "p", "..", "my-project/..", "My-Project")) {
            assertTrue(project, runCatching { VertexUrls.base("global", project) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun theLogPathHidesTheProject() {
        val url = "https://aiplatform.googleapis.com/v1/projects/my-project/locations/global/publishers/google/models/g:generateContent".toHttpUrl()

        assertEquals("/v1/projects/{project}/locations/global/publishers/google/models/g:generateContent", VertexUrls.logPath(url))
    }
}
