package dev.kestrel.providers.signin

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.signin.SignInFixtures.catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The flows' keys are the catalog's choice IDs (design D9). */
class SignInRegistryTest {
    @Test
    fun everyFlowsChoiceExistsInTheCatalogWithItsMethod() {
        for ((key, kind) in SignInRegistry.flows) {
            val choice = catalog.choice(key.first, key.second)?.settings
            val expected = if (kind == SignInKind.CHATGPT_PLAN || kind == SignInKind.OPENROUTER) AuthMethod.BROWSER else AuthMethod.DEVICE_CODE
            assertEquals("$key", expected, choice?.method)
            assertTrue("$key", choice!!.offered)
        }
    }

    @Test
    fun everyOfferedDeviceCodeOrBrowserChoiceHasAFlow() {
        val signIns = catalog.providers.flatMap { provider ->
            provider.authChoices.mapNotNull { it.settings }
                .filter { it.offered && (it.method == AuthMethod.DEVICE_CODE || it.method == AuthMethod.BROWSER) }
                .map { provider.id to it.choiceId }
        }

        assertEquals(SignInRegistry.flows.keys, signIns.toSet())
    }
}
