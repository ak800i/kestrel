package dev.kestrel.app.providers

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

/** The setup checklist's provider step reads the catalog and the credential off its collector's thread (design D18). */
class ProviderReadinessTest {
    private val catalog = CatalogLoader.load(
        """{"schema": 1, "openclawCommit": "e6113549fe8800b4c736c5e8a131b47f2e015143", "providers": [
             {"id": "acme", "plugin": "acme", "label": "Acme", "style": "RESPONSES", "baseUrl": "https://api.acme.test/v1", "hosts": ["api.acme.test"],
              "authChoices": [{"choiceId": "acme-key", "method": "api-key", "label": "Key"}], "models": []}]}""",
        """{"schema": 1, "excludeProviders": [], "excludeAuthChoices": [],
            "choices": [{"provider": "acme", "choiceId": "acme-key", "method": "API_KEY"}],
            "providers": [], "localServers": [], "models": [], "freeModels": [],
            "unknownPrice": {"input": 5.0, "output": 25.0},
            "copilot": {"pricesCopiedOn": "2026-11-17", "pricesSource": "https://docs.github.com/", "models": [], "lightweight": [], "versatile": [],
                        "runtimeHeaders": {}, "ideHeaders": {}}}""",
    )

    private val profile =
        Profile("test-1", "Test", "acme", "acme-key", AuthMethod.API_KEY, "test-key-1", lightweight = ProfileModel("a"), versatile = ProfileModel("b"))
    private val ready = ProvidersSetting(profiles = listOf(profile), defaultProfileId = profile.id)

    private val threads = Collections.synchronizedList(mutableListOf<String>())

    private fun recordingCatalog() = run {
        threads += Thread.currentThread().name
        catalog
    }

    private fun recordingRead(credential: Credential?): suspend (String) -> Credential? = {
        threads += Thread.currentThread().name
        credential
    }

    @Test
    fun theCatalogAndTheCredentialAreReadOnTheGivenDispatcher() {
        Executors.newSingleThreadExecutor { Thread(it, "test-readiness-io") }.asCoroutineDispatcher().use { io ->
            val value = runBlocking {
                ProviderGraph.readiness(flowOf(ready), ::recordingCatalog, recordingRead(Credential.ApiKey("test-key")), io).first()
            }

            assertEquals(true, value)
            assertEquals(listOf("test-readiness-io", "test-readiness-io"), threads.map { it.substringBefore(" @") })
            assertFalse(Thread.currentThread().name.startsWith("test-readiness-io"))
        }
    }

    @Test
    fun theValuesFollowTheSettingInOrder() {
        Executors.newSingleThreadExecutor { Thread(it, "test-readiness-io") }.asCoroutineDispatcher().use { io ->
            val stored = MutableStateFlow<ProvidersSetting?>(null)
            val seen = runBlocking {
                val values = mutableListOf<Boolean>()
                val job = launch {
                    ProviderGraph.readiness(stored, ::recordingCatalog, recordingRead(null), io).take(2).toList(values)
                }
                stored.value = ProvidersSetting(profiles = listOf(profile))
                while (values.isEmpty()) yield()
                stored.value = ready.copy(profiles = listOf(profile.copy(credentialId = null)))
                job.join()
                values
            }

            // No default profile, then a default profile without a credential to read.
            assertEquals(listOf(false, true), seen)
        }
    }

    @Test
    fun aCredentialThatCannotBeReadMakesTheStepNotDoneAndIsReportedByItsClass() {
        val reported = mutableListOf<Throwable>()
        val value = runBlocking {
            val failing: suspend (String) -> Credential? = { throw java.security.KeyStoreException("test: the Keystore failed") }
            ProviderGraph.readiness(flowOf(ready), { catalog }, failing, kotlinx.coroutines.Dispatchers.Unconfined) { _, e -> reported += e }.first()
        }

        assertEquals(false, value)
        assertEquals(listOf(java.security.KeyStoreException::class), reported.map { it::class })
    }

    @Test
    fun aDefaultProfileWhoseChoiceTheCatalogNoLongerHasIsNotReady() {
        val gone = profile.copy(choiceId = "acme-removed")
        val value = runBlocking {
            ProviderGraph.readiness(flowOf(ProvidersSetting(listOf(gone), gone.id)), { catalog }, { Credential.ApiKey("test-key") }).first()
        }

        assertEquals(false, value)
    }
}
