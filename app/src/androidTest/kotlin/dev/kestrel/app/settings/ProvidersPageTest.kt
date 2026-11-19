package dev.kestrel.app.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.R
import dev.kestrel.app.providers.CheckResult
import dev.kestrel.app.providers.ProviderTexts
import dev.kestrel.app.providers.SignInState
import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.client.ModelDefaults
import dev.kestrel.providers.copilot.CopilotQuota
import dev.kestrel.providers.cost.Spending
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.providers.signin.SignInFailure
import dev.kestrel.providers.signin.SignInResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The page "Providers and profiles" with a fake data layer (design D18 and D19), so that it never adds a profile for a
 * remote provider and never touches the owner's profiles: a local server with a key, the masked key, "Change key", the
 * default, the deletion, the switches, the links to the terms, and the ChatGPT plan's notices after a fake sign-in.
 */
class ProvidersPageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val data = FakeProviderData()

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun show() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ProvidersPage(data) {} } } }
    }

    private fun type(label: String, value: String) {
        compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performTextInput(value)
    }

    /** Taps the first control that shows [label], such as a button or a page's entry, but not a title or a text field with the same text. */
    private fun tap(label: String) {
        compose.onAllNodes(hasText(label) and hasClickAction() and !hasSetTextAction()).onFirst().performScrollTo().performClick()
    }

    /** Taps a button of a dialog. A dialog does not scroll, so performScrollTo would fail there. */
    private fun tapInDialog(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
    }

    private fun shows(label: String): Boolean = compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

    private fun addProvider(search: String, provider: String, choice: String) {
        tap(text(R.string.providers_add))
        type(text(R.string.providers_search), search)
        tap(provider)
        tap(choice)
    }

    @Test
    fun aLocalServerWithAKeyIsCheckedSavedAndItsKeyShownMasked() {
        show()
        addProvider("Ollama", "Ollama", "Server address")
        type(text(R.string.providers_server_key), "sk-test-a1b2")
        tap(text(R.string.providers_check))
        compose.waitUntil(5_000) { shows(text(R.string.providers_save)) }

        tap(text(R.string.providers_save))
        compose.waitUntil(5_000) { data.saved.isNotEmpty() }

        val (profile, credential) = data.saved.single()
        assertEquals("http://127.0.0.1:11434/", profile.baseUrl)
        assertEquals(LocalServerKind.OLLAMA, profile.localServer)
        assertEquals(Credential.ApiKey("sk-test-a1b2"), credential)
        assertEquals("llama-test", profile.lightweight.id)
        tap("Ollama (${text(R.string.providers_default)})")
        compose.onNodeWithText(text(R.string.providers_key_masked, "…a1b2")).performScrollTo()
    }

    @Test
    fun aProfileCanChangeItsKeyBecomeTheDefaultAndBeDeleted() {
        data.setting.value = ProvidersSetting(listOf(KEY_PROFILE, OTHER_PROFILE), defaultProfileId = KEY_PROFILE.id)
        show()

        tap("Second")
        // A sign-in that this page did not start, as an add flow's, never replaces the profile's credential (design D10).
        compose.waitForIdle()
        data.signIn.value = SignInState.Succeeded("groq", "groq-api-key", SignInResult(Credential.ApiKey("sk-test-stale1")))
        compose.waitForIdle()
        tap(text(R.string.providers_make_default))
        type(text(R.string.providers_key), "sk-test-new9")
        tap(text(R.string.providers_change_key))
        tap(text(R.string.providers_delete))
        tapInDialog(text(R.string.providers_delete_yes))
        compose.waitUntil(5_000) { data.deleted.isNotEmpty() }

        assertEquals(listOf(OTHER_PROFILE.id), data.defaults)
        assertEquals(listOf(OTHER_PROFILE.id to Credential.ApiKey("sk-test-new9")), data.replaced)
        assertEquals(listOf(OTHER_PROFILE.id), data.deleted)
    }

    @Test
    fun theBorrowedSignInsHaveSwitchesAndTheLinksToTheirTerms() {
        show()

        compose.onNodeWithContentDescription(text(R.string.providers_switch_xai)).performScrollTo().assertIsOff().performClick()
        compose.waitUntil(5_000) { data.switches.isNotEmpty() }

        assertEquals(listOf("xai" to true), data.switches)
        assertTrue(shows("Acceptable Use Policies"))
        assertTrue(shows("M Plan FAQ"))
    }

    @Test
    fun theChatGptPlanShowsOpenAisNoticeAfterItsFirstSignInAndItsStates() {
        show()
        tap(text(R.string.providers_add))
        type(text(R.string.providers_search), "OpenAI")
        tap("OpenAI")
        // openai-api-key has no label in overrides.json, so its card shows the catalog's label (design D18).
        assertTrue(shows("OpenAI API Key"))
        // The list of methods says that the ChatGPT plan uses OpenAI's own sign-in (decision 2).
        assertTrue(shows(text(R.string.providers_chatgpt_official)))
        tap("Continue with ChatGPT")
        data.onSignIn = { _ -> data.signIn.value = SignInState.Failed("openai", "chatgpt-plan", SignInFailure.PLAN_USE_NOT_ALLOWED, null) }
        tap("Continue with ChatGPT")
        compose.onNodeWithText("ChatGPT plan use was not allowed.").performScrollTo()

        data.onSignIn = { consent ->
            assertTrue(consent)
            val token = Credential.OAuthToken("test-access", "test-refresh", null, clientId = "oaiapp_test")
            data.signIn.value = SignInState.Succeeded("openai", "chatgpt-plan", SignInResult(token))
        }
        tap(text(R.string.providers_allow_plan))
        compose.waitUntil(5_000) { shows(text(R.string.providers_save)) }
        tap(text(R.string.providers_save))

        compose.waitUntil(5_000) { shows(text(R.string.providers_plan_dialog_title)) }
        assertTrue(shows(text(R.string.providers_manage_usage)))
        tapInDialog(text(R.string.providers_ok))
        // The save ended the add flow, so Kestrel forgot the sign-in and its token (design D10).
        compose.waitUntil(5_000) { data.signIn.value == SignInState.Idle }
        tap("OpenAI (${text(R.string.providers_default)})")
        compose.onNodeWithText(text(R.string.providers_using_plan)).performScrollTo()
    }

    @Test
    fun aSecondTapOnSaveWhileTheFirstSaveRunsSavesOnce() {
        val gate = CompletableDeferred<Unit>()
        data.saveGate = gate
        show()
        addProvider("Ollama", "Ollama", "Server address")
        type(text(R.string.providers_server_key), "sk-test-c3d4")
        tap(text(R.string.providers_check))
        compose.waitUntil(5_000) { shows(text(R.string.providers_save)) }

        tap(text(R.string.providers_save))
        compose.waitUntil(5_000) { data.saving == 1 }
        compose.onNode(hasText(text(R.string.providers_save)) and hasClickAction()).assertIsNotEnabled()
        tap(text(R.string.providers_save))
        gate.complete(Unit)
        compose.waitUntil(5_000) { data.saved.isNotEmpty() }
        compose.waitForIdle()

        assertEquals(1, data.saving)
        assertEquals(1, data.saved.size)
    }

    @Test
    fun aSignInAgainThatFailedWhileTheOwnerWasAwayShowsWhenHeComesBack() {
        data.setting.value = ProvidersSetting(listOf(BROWSER_PROFILE), defaultProfileId = BROWSER_PROFILE.id)
        show()
        tap("Router (${text(R.string.providers_default)})")
        tap(text(R.string.providers_sign_in_again))
        compose.waitUntil(5_000) { data.signIn.value is SignInState.Waiting }
        tap(text(R.string.settings_back))
        compose.waitUntil(5_000) { shows(text(R.string.providers_add)) }

        // The sign-in goes on in the graph and fails while the profile page is closed.
        data.signIn.value = SignInState.Failed("openrouter", "openrouter-oauth", SignInFailure.NO_CALLBACK, null, BROWSER_PROFILE.id)
        tap("Router (${text(R.string.providers_default)})")

        compose.onNodeWithText("The browser did not come back to Kestrel within 5 minutes.").performScrollTo()
    }

    @Test
    fun useAnApiKeyOnAProfilePageOpensTheKeyEntryOfTheAddFlow() {
        data.setting.value = ProvidersSetting(listOf(BROWSER_PROFILE), defaultProfileId = BROWSER_PROFILE.id)
        data.onSignIn = { _ -> data.signIn.value = SignInState.Failed("openrouter", "openrouter-oauth", SignInFailure.REFUSED, null, BROWSER_PROFILE.id) }
        show()
        tap("Router (${text(R.string.providers_default)})")
        tap(text(R.string.providers_sign_in_again))
        compose.waitUntil(5_000) { shows(text(R.string.providers_use_key)) }

        tap(text(R.string.providers_use_key))

        compose.onNode(hasSetTextAction() and hasText(text(R.string.providers_key))).performScrollTo()
        assertEquals(SignInState.Idle, data.signIn.value)
    }

    @Test
    fun aFailureOnAProfilePageNamesTheProviderNotTheProfilesId() {
        data.setting.value = ProvidersSetting(listOf(KEY_PROFILE), defaultProfileId = KEY_PROFILE.id)
        data.modelsError = ProviderError.Unreachable
        show()

        tap("First (${text(R.string.providers_default)})")

        val label = data.catalog.provider("groq")!!.label
        compose.onNodeWithText(ProviderTexts.check(ProviderError.Unreachable, label)).performScrollTo()
        assertTrue(compose.onAllNodes(hasText(KEY_PROFILE.id, substring = true)).fetchSemanticsNodes().isEmpty())
    }

    private companion object {
        val KEY_PROFILE = Profile(
            "test-first", "First", "groq", "groq-api-key", AuthMethod.API_KEY, "test-first-key",
            lightweight = ProfileModel("a"), versatile = ProfileModel("b"),
        )
        val OTHER_PROFILE = KEY_PROFILE.copy(id = "test-second", name = "Second", credentialId = "test-second-key")
        val BROWSER_PROFILE = Profile(
            "test-router", "Router", "openrouter", "openrouter-oauth", AuthMethod.BROWSER, "test-router-key",
            lightweight = ProfileModel("a"), versatile = ProfileModel("b"),
        )
    }
}

/**
 * The page's data in memory; the catalog is the app's own, read from its assets. As ProviderGraph does, a profile's "Sign
 * in again" carries the profile's ID, and leaving the add flow ends only the add flow's sign-in.
 */
internal class FakeProviderData : ProviderData {
    override val catalog: Catalog = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph.catalog
    override val setting = MutableStateFlow(ProvidersSetting())
    override val spending = MutableStateFlow(Spending())
    override val signIn = MutableStateFlow<SignInState>(SignInState.Idle)
    override val browserPage = MutableStateFlow<String?>(null)
    override val planLimitReached = MutableStateFlow(emptySet<String>())
    var onSignIn: (consent: Boolean) -> Unit = {}

    /** When set, each save waits for it, as a save that is still writing. */
    var saveGate: CompletableDeferred<Unit>? = null
    var saving = 0
    var modelsError: ProviderError? = null
    val saved = mutableListOf<Pair<Profile, Credential?>>()
    val defaults = mutableListOf<String>()
    val replaced = mutableListOf<Pair<String, Credential>>()
    val deleted = mutableListOf<String>()
    val switches = mutableListOf<Pair<String, Boolean>>()
    private val keys = mutableMapOf<String, Credential.ApiKey>()

    override fun pageOpened() {
        browserPage.value = null
    }

    override fun reopenPage() = Unit

    override suspend fun signInAgain(profileId: String) {
        val profile = setting.value.profiles.first { it.id == profileId }
        startSignIn(profile.providerId, profile.choiceId, null, consent = false, newRegistration = false, profileId = profileId)
    }

    override fun startSignIn(providerId: String, choiceId: String, tenant: String?, consent: Boolean, newRegistration: Boolean, profileId: String?) {
        signIn.value = SignInState.Waiting(providerId, choiceId, profileId = profileId)
        onSignIn(consent)
    }

    override fun cancelSignIn() {
        signIn.value = SignInState.Idle
    }

    override fun leaveAddFlow() {
        if (signIn.value.profileId == null) cancelSignIn()
    }

    override fun localNetworkAllowed(host: String) = true

    override suspend fun check(draft: Profile, credential: Credential?): CheckResult {
        val row = ModelEntry("llama-test", "Llama test")
        return CheckResult(draft, listOf(row), listOf(DiscoveredModel(row.id, row.name)), ModelDefaults(row.id, row.id))
    }

    override suspend fun save(draft: Profile, credential: Credential?, discovered: List<DiscoveredModel>, makeDefault: Boolean): Profile {
        saving++
        saveGate?.await()
        val profile = draft.copy(credentialId = credential?.let { "test-${draft.id}" })
        saved += profile to credential
        (credential as? Credential.ApiKey)?.let { keys[profile.id] = it }
        val current = setting.value
        setting.value = current.copy(profiles = current.profiles + profile, defaultProfileId = current.defaultProfileId ?: profile.id)
        return profile
    }

    override suspend fun delete(profileId: String): Boolean {
        deleted += profileId
        setting.value = setting.value.copy(profiles = setting.value.profiles.filterNot { it.id == profileId })
        return true
    }

    override suspend fun setDefault(profileId: String) {
        defaults += profileId
        setting.value = setting.value.copy(defaultProfileId = profileId)
    }

    override suspend fun rename(profileId: String, name: String) = Unit

    override suspend fun setModel(profileId: String, role: ModelRole, model: ProfileModel) = Unit

    override suspend fun setSwitch(identity: String, on: Boolean) {
        switches += identity to on
    }

    override suspend fun replaceCredential(profileId: String, result: SignInResult) {
        replaced += profileId to result.credential
        (result.credential as? Credential.ApiKey)?.let { keys[profileId] = it }
    }

    override suspend fun maskedKey(profileId: String): String? = keys[profileId]?.let { "…" + it.key.takeLast(4) }

    override suspend fun models(profileId: String): List<ModelEntry> = modelsError?.let { throw ProviderException(it) } ?: emptyList()

    override suspend fun refreshModels(profileId: String): List<ModelEntry> = emptyList()

    override suspend fun allowance(profileId: String): List<CopilotQuota> = emptyList()

    override fun priceKnown(profile: Profile, modelId: String): Boolean = true
}
