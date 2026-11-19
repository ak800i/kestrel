package dev.kestrel.providers.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotDomainTest {
    @Test
    fun copilotsApiMayLiveOnlyOnGitHubsHostsOrTheTenantsOwn() {
        assertTrue(CopilotDomain.trusted("copilot-proxy.githubusercontent.com", "github.com"))
        assertTrue(CopilotDomain.trusted("api.business.githubcopilot.com", "github.com"))
        assertTrue(CopilotDomain.trusted("copilot-api.acme.ghe.com", "acme.ghe.com"))
        assertTrue(CopilotDomain.trusted("acme.ghe.com", "acme.ghe.com"))
        assertFalse(CopilotDomain.trusted("copilot-api.acme.ghe.com", "github.com"))
        assertFalse(CopilotDomain.trusted("githubcopilot.com.example.com", "github.com"))
        assertFalse(CopilotDomain.trusted("evil-acme.ghe.com", "acme.ghe.com"))
    }

    @Test
    fun anApiAddressMustBeHttpsWithoutUserQueryOrFragment() {
        assertEquals("https://api.individual.githubcopilot.com/", CopilotDomain.accept("https://api.individual.githubcopilot.com/", "github.com").toString())
        assertNull(CopilotDomain.accept("http://api.individual.githubcopilot.com", "github.com"))
        assertNull(CopilotDomain.accept("https://user@api.individual.githubcopilot.com", "github.com"))
        assertNull(CopilotDomain.accept("https://api.individual.githubcopilot.com/?a=1", "github.com"))
        assertNull(CopilotDomain.accept("https://api.individual.githubcopilot.com/#a", "github.com"))
        assertNull(CopilotDomain.accept("https://api.example.com", "github.com"))
        assertEquals("https://api.individual.githubcopilot.com/", CopilotDomain.fallbackBase("github.com").toString())
        assertEquals("https://copilot-api.acme.ghe.com/", CopilotDomain.fallbackBase("acme.ghe.com").toString())
    }
}
