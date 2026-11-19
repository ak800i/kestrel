package dev.kestrel.app.providers

import android.content.Context
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.providerModels
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.settings.SettingsStore
import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.client.LocalNetworkAccess
import dev.kestrel.providers.client.ModelDefaults
import dev.kestrel.providers.client.OfferedModels
import dev.kestrel.providers.client.PlanLimits
import dev.kestrel.providers.client.ProfileClient
import dev.kestrel.providers.client.ProvidersRepository
import dev.kestrel.providers.client.TargetResolver
import dev.kestrel.providers.copilot.CopilotAllowance
import dev.kestrel.providers.copilot.CopilotDiscovery
import dev.kestrel.providers.copilot.CopilotModels
import dev.kestrel.providers.copilot.CopilotQuota
import dev.kestrel.providers.copilot.CopilotTargets
import dev.kestrel.providers.cost.Spending
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialManager
import dev.kestrel.providers.credentials.CredentialVault
import dev.kestrel.providers.discovery.ChatGptPlanModels
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.discovery.Discoveries
import dev.kestrel.providers.discovery.ModelList
import dev.kestrel.providers.discovery.XaiModels
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RefreshResult
import dev.kestrel.providers.profiles.ChatGptRegistration
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileHosts
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.providers.signin.BrowserFlow
import dev.kestrel.providers.signin.ChatGptPlanFlow
import dev.kestrel.providers.signin.CheckMethod
import dev.kestrel.providers.signin.DeviceCodeFlow
import dev.kestrel.providers.signin.DeviceCodePrompt
import dev.kestrel.providers.signin.Entries
import dev.kestrel.providers.signin.GitHubDeviceFlow
import dev.kestrel.providers.signin.LoopbackListener
import dev.kestrel.providers.signin.MiniMaxDeviceFlow
import dev.kestrel.providers.signin.MiniMaxRegion
import dev.kestrel.providers.signin.OpenRouterBrowserFlow
import dev.kestrel.providers.signin.SignInFailed
import dev.kestrel.providers.signin.SignInFailure
import dev.kestrel.providers.signin.SignInKind
import dev.kestrel.providers.signin.SignInRegistry
import dev.kestrel.providers.signin.SignInResult
import dev.kestrel.providers.signin.XaiDeviceFlow
import dev.kestrel.providers.styles.Adapters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import java.util.UUID

/** Where a sign-in stands, for the sign-in page and SignInService (design D9 and D10). */
sealed interface SignInState {
    data object Idle : SignInState

    /** A sign-in runs; [prompt] is the device code once the provider gave it. */
    data class Waiting(val providerId: String, val choiceId: String, val prompt: DeviceCodePrompt? = null) : SignInState

    data class Succeeded(val providerId: String, val choiceId: String, val result: SignInResult) : SignInState

    /** [failure] is a reason that the page explains; [error] a transport or HTTP failure. */
    data class Failed(val providerId: String, val choiceId: String, val failure: SignInFailure?, val error: ProviderError?) : SignInState
}

/** What a check found before the owner saves the profile (design D9 and D13). Model names can be the owner's own. */
class CheckResult(val draft: Profile, val rows: List<ModelEntry>, val discovered: List<DiscoveredModel>, val defaults: ModelDefaults) {
    override fun toString(): String = "CheckResult(draft=$draft, rows=${rows.size}, discovered=${discovered.size})"
}

/**
 * The providers' part of Kestrel's graph (design D9 to D13, D15 and D18): the credential manager, the clients that
 * sub-project 5 uses, the sign-ins with their leases on the allowlist, the add flow's check, and the changes that the
 * Providers page makes. A new credential stays in memory until the owner saves its profile; Kestrel forgets it when the
 * owner leaves the add flow or its process ends. Changes of the stored setting are made one at a time.
 */
internal class ProviderGraph(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    loadCatalog: () -> Catalog,
    val http: ProviderHttp,
    vault: CredentialVault,
    val applied: StateFlow<ProvidersSetting?>,
    private val policy: NetworkPolicy,
    private val report: (String, Throwable) -> Unit,
    private val loopback: LoopbackListener = LoopbackListenerAdapter(),
    val localNetwork: LocalNetworkAccess = AndroidLocalNetworkAccess(context),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val catalog: Catalog by lazy(loadCatalog)
    val credentials: CredentialManager by lazy { CredentialManager(vault, http, catalog, scope, clock) }
    private val writes = Mutex()

    /** The stored provider setting, null until the store has answered. */
    val stored: StateFlow<ProvidersSetting?> = settings.observe(SettingKeys.PROVIDERS)
        .catch { report("Reading the provider setting", it) }
        .stateIn<ProvidersSetting?>(scope, SharingStarted.Eagerly, null)

    private val copilot by lazy { CopilotTargets(catalog, credentials, CopilotDiscovery(http)) { profile, base -> moveCopilotBase(profile, base) } }

    val resolver: TargetResolver by lazy {
        TargetResolver(catalog, credentials, { settings.get(SettingKeys.providerModels(it)).models }, mapOf(CatalogLoader.COPILOT to copilot))
    }

    /** What sub-project 5 asks for a profile's client (design D13). */
    val repository: ProvidersRepository by lazy { ProvidersRepository(stored, applied, ::client) }

    /** The adapter of a style; the debug hooks replace nothing here. */
    fun adapter(style: RequestStyle): ChatAdapter = Adapters.forStyle(style, http)

    private fun client(profile: Profile, limits: PlanLimits) =
        ProfileClient(profile, catalog, applied, resolver::resolve, ::adapter, localNetwork, ::addSpending, limits, clock)

    /** Adds a call's credits and tokens to the month's spending (design D14). */
    suspend fun addSpending(profileId: String, month: String, credits: Double, tokens: Long) = writes.withLock {
        settings.set(SettingKeys.SPENDING, settings.get(SettingKeys.SPENDING).add(profileId, month, credits, tokens))
    }

    val spending: Flow<Spending> = settings.observe(SettingKeys.SPENDING)

    /**
     * Whether the setup checklist's step is done (design D18): a default profile exists, its credential exists (or it is a
     * local server without a key), and its borrowed sign-in is not switched off.
     */
    val providerReady: Flow<Boolean> = readiness(stored, { catalog }, { credentials.read(it) })

    // The sign-ins.

    private val signInState = SignInStates()
    val signIn: StateFlow<SignInState> = signInState.flow

    private val pages = MutableStateFlow<String?>(null)

    /** A sign-in page that the settings activity opens in a Custom Tab, then clears with [pageOpened]. */
    val browserPage: StateFlow<String?> = pages.asStateFlow()

    /** Whether Kestrel opened a Custom Tab for the running sign-in. */
    @Volatile
    var tabOpened = false
        private set

    /** Whether the settings activity is in front, which MainActivity sets in onResume and onPause. */
    @Volatile
    var settingsResumed = false

    @Volatile
    private var job: Job? = null

    /** The ChatGPT plan's issued client ID that a failed sign-in of this add flow returned, for "Try again" (design D9). */
    @Volatile
    private var pendingRegistration: ChatGptRegistration? = null

    /**
     * The settings activity opened a page of the running sign-in in a Custom Tab: the browser sign-in's page, or the device
     * code's verification address. After the sign-in, SignInService brings the settings back when they are not in front.
     */
    fun pageOpened() {
        pages.value = null
        if (signInState.value is SignInState.Waiting) tabOpened = true
    }

    /** The running browser sign-in's page, so that the owner can open it again after he closed its tab. */
    @Volatile
    private var lastPage: String? = null

    /** Asks the settings activity to open the running browser sign-in's page again. */
    fun reopenPage() {
        if (signInState.value is SignInState.Waiting) lastPage?.let { pages.value = it }
    }

    /**
     * Starts the sign-in of [choiceId] on [providerId] and SignInService. [tenant] is a GHE.com tenant for Copilot's
     * enterprise choice; [consent] asks again for ChatGPT plan use; [newRegistration] registers Kestrel with another
     * ChatGPT account, replacing the kept registration.
     */
    fun startSignIn(providerId: String, choiceId: String, tenant: String? = null, consent: Boolean = false, newRegistration: Boolean = false) {
        val kind = SignInRegistry.kind(providerId, choiceId) ?: return
        job?.cancel()
        tabOpened = false
        pages.value = null
        // A GHE.com tenant that is not one fails before the service starts.
        if (kind == SignInKind.GITHUB_TENANT && tenant?.let(GitHubDeviceFlow::tenant) == null) {
            signInState.end { SignInState.Failed(providerId, choiceId, SignInFailure.UNTRUSTED_ADDRESS, null) }
            return
        }
        val ticket = signInState.begin(SignInState.Waiting(providerId, choiceId))
        SignInService.start(context, catalog.provider(providerId)?.label ?: providerId)
        job = scope.launch {
            val outcome = try {
                SignInState.Succeeded(providerId, choiceId, runFlow(ticket, kind, tenant, consent, newRegistration))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignInFailed) {
                // OpenAI refused this add flow's issued client ID, or it is another account's: the next sign-in registers again.
                if (e.kind == SignInFailure.REGISTRATION_REFUSED || e.kind == SignInFailure.OTHER_ACCOUNT) pendingRegistration = null
                keep(e)
                SignInState.Failed(providerId, choiceId, e.kind, null)
            } catch (e: ProviderException) {
                SignInState.Failed(providerId, choiceId, null, e.error)
            } catch (e: Exception) {
                // A failure that no flow names: the sign-in ends instead of waiting for ever. Logged by its classes only.
                report("A sign-in", e)
                SignInState.Failed(providerId, choiceId, SignInFailure.EXCHANGE_FAILED, null)
            }
            // Only the current sign-in ends itself: after a cancel or a newer sign-in, this outcome is dropped (design D10).
            signInState.set(ticket) { outcome }
        }
    }

    /**
     * "Sign in again" on a profile's page: the sign-in of its choice, on the GHE.com tenant of its GitHub token for
     * Copilot's enterprise choice.
     */
    suspend fun signInAgain(profileId: String) {
        val profile = stored.value?.profiles?.firstOrNull { it.id == profileId } ?: return
        val tenant = (profile.credentialId?.let { credentials.read(it) } as? Credential.GitHubToken)?.domain
        startSignIn(profile.providerId, profile.choiceId, tenant)
    }

    /** Android ended SignInService after its time (Android 15 and later): the sign-in ends with a clear sentence. */
    fun timeOutSignIn() {
        signInState.end { state -> (state as? SignInState.Waiting)?.let { SignInState.Failed(it.providerId, it.choiceId, SignInFailure.TIMED_OUT, null) } }
            ?: return
        job?.cancel()
        job = null
        pages.value = null
    }

    /** Ends the running sign-in, as the page's and the notification's Cancel do. */
    fun cancelSignIn() {
        // The cancel can come before SignInService's watcher has seen the sign-in wait, so the service is told to stop too.
        val waiting = signInState.end { SignInState.Idle } is SignInState.Waiting
        job?.cancel()
        job = null
        pages.value = null
        lastPage = null
        if (waiting) {
            try {
                SignInService.stop(context)
            } catch (e: IllegalStateException) {
                // Android refuses the start only while Kestrel is in the background without the service: nothing runs then.
            }
        }
    }

    /** Forgets the add flow's sign-in and pending registration when the owner leaves the add flow. */
    fun leaveAddFlow() {
        cancelSignIn()
        pendingRegistration = null
    }

    private suspend fun runFlow(ticket: Long, kind: SignInKind, tenant: String?, consent: Boolean, newRegistration: Boolean): SignInResult = when (kind) {
        SignInKind.GITHUB -> deviceCode(ticket, GitHubDeviceFlow(GitHubDeviceFlow.PUBLIC, http))
        SignInKind.GITHUB_TENANT -> deviceCode(ticket, GitHubDeviceFlow(tenant?.let(GitHubDeviceFlow::tenant) ?: throw IllegalArgumentException("Not a tenant"), http))
        SignInKind.XAI -> deviceCode(ticket, XaiDeviceFlow(http, { policy.openTemporaryHosts(it) }))
        SignInKind.MINIMAX_GLOBAL -> deviceCode(ticket, MiniMaxDeviceFlow(MiniMaxRegion.GLOBAL, http))
        SignInKind.MINIMAX_CHINA -> deviceCode(ticket, MiniMaxDeviceFlow(MiniMaxRegion.CHINA, http))
        SignInKind.CHATGPT_PLAN -> browser(ChatGptPlanFlow(chatgptHostId(), chatgptRegistration(newRegistration), consent, http))
        SignInKind.OPENROUTER -> browser(OpenRouterBrowserFlow(http))
    }.also { result -> result.registration?.let { registration -> update { it.copy(chatgptRegistration = registration) } } }

    private suspend fun deviceCode(ticket: Long, flow: DeviceCodeFlow): SignInResult = policy.openTemporaryHosts(flow.signInHosts).use {
        val prompt = flow.start()
        signInState.set(ticket) { (it as? SignInState.Waiting)?.copy(prompt = prompt) ?: it }
        flow.await(prompt)
    }

    private suspend fun browser(flow: BrowserFlow): SignInResult = policy.openTemporaryHosts(flow.signInHosts).use {
        flow.run(loopback) { page ->
            lastPage = page
            pages.value = page
        }
    }

    /** A failure's registration: kept in the setting when it has the account's `sub`, else only for this add flow. */
    private suspend fun keep(failure: SignInFailed) {
        val registration = failure.registration ?: return
        if (registration.subject != null) update { it.copy(chatgptRegistration = registration) } else pendingRegistration = registration
    }

    /** The installation's opaque host ID for the ChatGPT plan, made once (design D9). */
    private suspend fun chatgptHostId(): String {
        settings.get(SettingKeys.PROVIDERS).chatgptHostId?.let { return it }
        val made = "urn:uuid:${UUID.randomUUID()}"
        update { if (it.chatgptHostId == null) it.copy(chatgptHostId = made) else it }
        return settings.get(SettingKeys.PROVIDERS).chatgptHostId ?: made
    }

    /**
     * The registration that a ChatGPT plan sign-in uses: the issued client ID that this add flow already got, also for
     * "Register with this account", so that one add flow never registers Kestrel twice; else none for "Register with this
     * account", which ignores only the kept registration; else the kept one, which is cleared first when a ChatGPT plan
     * profile's credential lost its client ID to `invalid_client`, so that the sign-in registers Kestrel again (design D9).
     */
    private suspend fun chatgptRegistration(newRegistration: Boolean): ChatGptRegistration? {
        // A client ID that OpenAI issued in this add flow is reused, also by "Register with this account" (design D9).
        pendingRegistration?.let { return it }
        if (newRegistration) return null
        val setting = settings.get(SettingKeys.PROVIDERS)
        // Only a token that lost its client ID to invalid_client clears it, not a missing or unreadable credential.
        val cleared = setting.profiles.filter { chatgptPlan(it) }.any { profile ->
            val token = profile.credentialId?.let { credentials.read(it) } as? Credential.OAuthToken
            token != null && token.clientId == null
        }
        if (cleared) {
            update { it.copy(chatgptRegistration = null) }
            return null
        }
        return setting.chatgptRegistration
    }

    private fun chatgptPlan(profile: Profile): Boolean = catalog.choice(profile.providerId, profile.choiceId)?.settings?.chatgptPlan == true

    // The add flow and the profile page.

    /**
     * The add flow's "Check" (design D9, D11 and D13): with the new [credential] held in memory and the future profile's
     * hosts leased on the allowlist (its local server through the local-server lease), a model discovery, Vertex's token
     * exchange, or one short request with the model that the owner chose first. Returns the offered rows and their
     * defaults, and the draft with what the check found (Copilot's API base and host). Throws ProviderException or
     * SignInFailed.
     */
    suspend fun check(draft: Profile, credential: Credential?): CheckResult {
        val held = credential?.let { credentials.hold(it) }
        val leases = mutableListOf<AutoCloseable>()
        try {
            var profile = draft.copy(credentialId = held ?: draft.credentialId)
            leases += policy.openTemporaryHosts(ProfileHosts.hosts(profile, catalog))
            ProfileHosts.localServers(profile).takeIf { it.isNotEmpty() }?.let { leases += policy.openTemporaryLocalServers(it) }
            val provider = catalog.provider(profile.providerId) ?: throw ProviderException(ProviderError.NotAllowed)
            val choice = catalog.choice(profile.providerId, profile.choiceId)?.settings
            val discovered = when (Entries.checkMethod(provider, choice)) {
                CheckMethod.DISCOVERY -> {
                    if (profile.providerId == CatalogLoader.COPILOT) {
                        val token = credentials.read(profile.credentialId!!) as? Credential.GitHubToken ?: throw ProviderException(ProviderError.SignInExpired(profile.providerId))
                        val base = CopilotDiscovery(http).apiBase(token)
                        leases += policy.openTemporaryHosts(setOf(base.host))
                        profile = profile.copy(baseUrl = base.toString(), discoveredHosts = (profile.discoveredHosts + base.host).distinct())
                    }
                    discover(profile) ?: run {
                        shortRequest(profile)
                        emptyList()
                    }
                }
                CheckMethod.TOKEN_EXCHANGE -> {
                    // Plan 4B's token cache could answer for a new key file of an account that the process already holds,
                    // so the check always exchanges the key at Google.
                    val auth = credentials.auth(profile, KeyHeader.BEARER)
                    when (val result = auth.refresh()) {
                        RefreshResult.Refreshed -> Unit
                        is RefreshResult.FailedForNow -> throw ProviderException(result.error)
                        RefreshResult.Expired, RefreshResult.NotPossible -> throw ProviderException(auth.rejected)
                    }
                    emptyList()
                }
                CheckMethod.SHORT_REQUEST -> {
                    shortRequest(profile)
                    emptyList()
                }
            }
            val rows = OfferedModels.rows(catalog, profile.providerId, profile.choiceId, discovered)
            return CheckResult(profile.copy(credentialId = draft.credentialId), rows, discovered, OfferedModels.defaults(catalog, profile.providerId, rows))
        } finally {
            leases.forEach { it.close() }
            held?.let(credentials::release)
        }
    }

    /** The profile's model list from its provider, or null when the provider has none (design D11 to D13). */
    private suspend fun discover(profile: Profile): List<DiscoveredModel>? {
        val provider = catalog.provider(profile.providerId) ?: return null
        val choice = catalog.choice(profile.providerId, profile.choiceId)?.settings
        val base = ProfileHosts.baseUrl(profile, catalog) ?: return null
        val style = choice?.style ?: provider.style ?: RequestStyle.CHAT_COMPLETIONS
        val auth = credentials.auth(profile, choice?.keyHeader ?: TargetResolver.defaultKeyHeader(style))
        return when {
            profile.providerId == CatalogLoader.COPILOT -> CopilotModels(http, base, auth, catalog.overrides.copilot.runtimeHeaders).list()
            SignInRegistry.kind(profile.providerId, profile.choiceId) == SignInKind.CHATGPT_PLAN -> ChatGptPlanModels(http, auth).list()
            SignInRegistry.kind(profile.providerId, profile.choiceId) == SignInKind.XAI -> XaiModels(http, auth, choice?.identityHeaders.orEmpty()).list()
            profile.localServer != null -> Discoveries.forLocalServer(profile.localServer!!, base, auth, http).list()
            else -> Discoveries.forProvider(provider, base, profile.region, auth, http)?.list()
        }
    }

    /** The check of a provider without a list: "Reply with OK." with the lightweight model (design D9). */
    private suspend fun shortRequest(profile: Profile) {
        val target = resolver.resolve(profile, ModelRole.LIGHTWEIGHT)
        adapter(target.style).complete(Entries.checkRequest(), target)
    }

    /**
     * Saves [draft] with its new [credential], if any, under the profile's credential ID or a new one, and its model list;
     * the first profile, or one that the owner marks, becomes the default. Kestrel forgets the add flow's pending
     * registration then.
     */
    suspend fun save(draft: Profile, credential: Credential?, discovered: List<DiscoveredModel>, makeDefault: Boolean): Profile {
        val id = credential?.let { credentials.store(it, draft.credentialId) } ?: draft.credentialId
        val signedIn = draft.method == AuthMethod.DEVICE_CODE || draft.method == AuthMethod.BROWSER
        val profile = draft.copy(credentialId = id, signedInAt = if (credential != null && signedIn) clock() else draft.signedInAt)
        settings.set(SettingKeys.providerModels(profile.id), ModelList(discovered, clock()))
        update { setting ->
            val profiles = setting.profiles.filterNot { it.id == profile.id } + profile
            setting.copy(profiles = profiles, defaultProfileId = if (makeDefault || setting.defaultProfile == null) profile.id else setting.defaultProfileId)
        }
        pendingRegistration = null
        // The save ends the add flow: Kestrel forgets its sign-in, so that the next add flow signs in again (design D10).
        signInState.end { if (it is SignInState.Succeeded) SignInState.Idle else null }
        tabOpened = false
        return profile
    }

    /**
     * Deletes a profile (design D9 and D15): for the ChatGPT plan it first revokes the refresh token, while
     * `auth.openai.com` is still on the allowlist; then it deletes the credential, the model list and the profile. The
     * ChatGPT registration and the host ID stay. Returns false when OpenAI did not confirm the sign-out.
     */
    suspend fun delete(profileId: String): Boolean {
        val profile = settings.get(SettingKeys.PROVIDERS).profiles.firstOrNull { it.id == profileId } ?: return true
        val credential = profile.credentialId?.let { credentials.read(it) }
        val confirmed = if (chatgptPlan(profile) && credential is Credential.OAuthToken) ChatGptPlanFlow.revoke(http, credential) else true
        profile.credentialId?.let { credentials.delete(it) }
        settings.set(SettingKeys.providerModels(profileId), ModelList())
        update { setting ->
            val profiles = setting.profiles.filterNot { it.id == profileId }
            setting.copy(profiles = profiles, defaultProfileId = setting.defaultProfileId.takeIf { it != profileId } ?: profiles.firstOrNull()?.id)
        }
        return confirmed
    }

    suspend fun setDefault(profileId: String) = update { it.copy(defaultProfileId = profileId) }

    suspend fun rename(profileId: String, name: String) = updateProfile(profileId) { it.copy(name = name) }

    suspend fun setModel(profileId: String, role: ModelRole, model: ProfileModel) =
        updateProfile(profileId) { if (role == ModelRole.LIGHTWEIGHT) it.copy(lightweight = model) else it.copy(versatile = model) }

    /** Switches a borrowed sign-in on or off for every profile that uses it (design D9). */
    suspend fun setSwitch(identity: String, on: Boolean) = update { it.copy(signInSwitches = it.signInSwitches + (identity to on)) }

    /** Replaces a profile's credential under its own ID, as "Sign in again" and "Change key" do (design D15). */
    suspend fun replaceCredential(profileId: String, result: SignInResult) {
        val profile = settings.get(SettingKeys.PROVIDERS).profiles.firstOrNull { it.id == profileId } ?: return
        val id = credentials.store(result.credential, profile.credentialId)
        val signedIn = profile.method == AuthMethod.DEVICE_CODE || profile.method == AuthMethod.BROWSER
        updateProfile(profileId) {
            it.copy(credentialId = id, signedInAt = if (signedIn) clock() else it.signedInAt, discoveredHosts = (it.discoveredHosts + result.hosts).distinct())
        }
        pendingRegistration = null
        // Another GitHub account may have another Copilot base, so the next request discovers it again.
        if (profile.providerId == CatalogLoader.COPILOT) copilot.forget(profileId)
    }

    suspend fun models(profileId: String): ModelList = settings.get(SettingKeys.providerModels(profileId))

    /** "Refresh models" on the profile page: the profile's list again, with its hosts leased. */
    suspend fun refreshModels(profileId: String): List<ModelEntry> {
        val profile = settings.get(SettingKeys.PROVIDERS).profiles.firstOrNull { it.id == profileId } ?: return emptyList()
        val discovered = policy.openTemporaryHosts(ProfileHosts.hosts(profile, catalog)).use { discover(profile) }.orEmpty()
        settings.set(SettingKeys.providerModels(profileId), ModelList(discovered, clock()))
        return OfferedModels.rows(catalog, profile.providerId, profile.choiceId, discovered)
    }

    /** "Check allowance" for a Copilot profile (design D12). */
    suspend fun allowance(profileId: String): List<CopilotQuota> {
        val profile = settings.get(SettingKeys.PROVIDERS).profiles.firstOrNull { it.id == profileId } ?: return emptyList()
        val token = profile.credentialId?.let { credentials.read(it) } as? Credential.GitHubToken
            ?: throw ProviderException(ProviderError.SignInExpired(profile.providerId))
        return CopilotAllowance(http, catalog.overrides.copilot.ideHeaders).check(token)
    }

    /** Stores Copilot's new API base and host, and waits up to 5 s until the allowlist holds it (design D12). */
    private suspend fun moveCopilotBase(profile: Profile, base: HttpUrl) {
        updateProfile(profile.id) { it.copy(baseUrl = base.toString(), discoveredHosts = (it.discoveredHosts + base.host).distinct()) }
        withTimeoutOrNull(ALLOWLIST_WAIT_MILLIS) {
            applied.first { setting -> setting?.profiles?.any { it.id == profile.id && it.baseUrl == base.toString() } == true }
        }
    }

    private suspend fun updateProfile(profileId: String, change: (Profile) -> Profile) =
        update { setting -> setting.copy(profiles = setting.profiles.map { if (it.id == profileId) change(it) else it }) }

    private suspend fun update(change: (ProvidersSetting) -> ProvidersSetting) = writes.withLock {
        settings.set(SettingKeys.PROVIDERS, change(settings.get(SettingKeys.PROVIDERS)))
    }

    companion object {
        /** The name of SettingsPage.PROVIDERS, which SignInService puts into MainActivity's EXTRA_PAGE. */
        const val PROVIDERS_PAGE = "PROVIDERS"
        private const val ALLOWLIST_WAIT_MILLIS = 5_000L

        /**
         * [providerReady] over the stored setting. It reads the catalog and the credential on [dispatcher], not in its
         * collector's context, because the settings collect it on the main thread and the first reads load the catalog's
         * assets and decrypt the credential with the Keystore.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        internal fun readiness(
            stored: Flow<ProvidersSetting?>,
            catalog: () -> Catalog,
            read: suspend (String) -> Credential?,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): Flow<Boolean> = stored.filterNotNull().mapLatest { setting ->
            val profile = setting.defaultProfile ?: return@mapLatest false
            val credential = profile.credentialId?.let { read(it) }
            setting.usable(profile, catalog()) && (credential != null || profile.credentialId == null)
        }.flowOn(dispatcher)
    }
}
