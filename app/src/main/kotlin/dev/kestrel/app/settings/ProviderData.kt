package dev.kestrel.app.settings

import dev.kestrel.app.providers.CheckResult
import dev.kestrel.app.providers.ProviderGraph
import dev.kestrel.app.providers.SignInState
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.client.LocalNetwork
import dev.kestrel.providers.client.OfferedModels
import dev.kestrel.providers.copilot.CopilotQuota
import dev.kestrel.providers.cost.PriceTable
import dev.kestrel.providers.cost.Spending
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.providers.signin.Entries
import dev.kestrel.providers.signin.SignInResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What the Providers page reads and changes (design D18). [StoredProviderData] connects it to the provider graph; the
 * page's device test uses a fake, so that it never adds a profile for a remote provider.
 */
interface ProviderData {
    val catalog: Catalog
    val setting: Flow<ProvidersSetting>
    val spending: Flow<Spending>
    val signIn: StateFlow<SignInState>

    /** A sign-in page that the page opens in a Custom Tab, then clears with [pageOpened]. */
    val browserPage: StateFlow<String?>

    /** The ChatGPT plan profiles whose plan's usage limit a call reached (design D17). */
    val planLimitReached: StateFlow<Set<String>>

    fun pageOpened()

    /** Opens the running browser sign-in's page again, after the owner closed its tab. */
    fun reopenPage()

    fun startSignIn(providerId: String, choiceId: String, tenant: String? = null, consent: Boolean = false, newRegistration: Boolean = false)

    fun cancelSignIn()

    /** "Sign in again" on a profile's page, on the GHE.com tenant of its GitHub token where it has one. */
    suspend fun signInAgain(profileId: String)

    fun leaveAddFlow()

    /** Whether Android lets Kestrel reach [host] now; false only for a host on the owner's network without the permission. */
    fun localNetworkAllowed(host: String): Boolean

    suspend fun check(draft: Profile, credential: Credential?): CheckResult

    suspend fun save(draft: Profile, credential: Credential?, discovered: List<DiscoveredModel>, makeDefault: Boolean): Profile

    /** Deletes the profile; false when OpenAI did not confirm the ChatGPT plan's sign-out. */
    suspend fun delete(profileId: String): Boolean

    suspend fun setDefault(profileId: String)

    suspend fun rename(profileId: String, name: String)

    suspend fun setModel(profileId: String, role: ModelRole, model: ProfileModel)

    suspend fun setSwitch(identity: String, on: Boolean)

    suspend fun replaceCredential(profileId: String, result: SignInResult)

    /** An API key's last four characters ("…a1b2"), or null for another credential. */
    suspend fun maskedKey(profileId: String): String?

    /** The rows that the profile offers, from its stored list and the catalog. */
    suspend fun models(profileId: String): List<ModelEntry>

    suspend fun refreshModels(profileId: String): List<ModelEntry>

    suspend fun allowance(profileId: String): List<CopilotQuota>

    /** Whether Kestrel knows the price of [modelId] on [profile] today (design D14). */
    fun priceKnown(profile: Profile, modelId: String): Boolean
}

internal class StoredProviderData(private val graph: ProviderGraph) : ProviderData {
    override val catalog: Catalog get() = graph.catalog
    override val setting: Flow<ProvidersSetting> = graph.stored.filterNotNull()
    override val spending: Flow<Spending> = graph.spending
    override val signIn: StateFlow<SignInState> = graph.signIn
    override val browserPage: StateFlow<String?> = graph.browserPage
    override val planLimitReached: StateFlow<Set<String>> get() = graph.repository.planLimitReached

    override fun pageOpened() = graph.pageOpened()

    override fun reopenPage() = graph.reopenPage()

    override suspend fun signInAgain(profileId: String) = graph.signInAgain(profileId)

    override fun startSignIn(providerId: String, choiceId: String, tenant: String?, consent: Boolean, newRegistration: Boolean) =
        graph.startSignIn(providerId, choiceId, tenant, consent, newRegistration)

    override fun cancelSignIn() = graph.cancelSignIn()

    override fun leaveAddFlow() = graph.leaveAddFlow()

    override fun localNetworkAllowed(host: String): Boolean = !LocalNetwork.needsPermission(host) || graph.localNetwork.allowed(host)

    override suspend fun check(draft: Profile, credential: Credential?): CheckResult = graph.check(draft, credential)

    override suspend fun save(draft: Profile, credential: Credential?, discovered: List<DiscoveredModel>, makeDefault: Boolean): Profile =
        graph.save(draft, credential, discovered, makeDefault)

    override suspend fun delete(profileId: String): Boolean = graph.delete(profileId)

    override suspend fun setDefault(profileId: String) = graph.setDefault(profileId)

    override suspend fun rename(profileId: String, name: String) = graph.rename(profileId, name)

    override suspend fun setModel(profileId: String, role: ModelRole, model: ProfileModel) = graph.setModel(profileId, role, model)

    override suspend fun setSwitch(identity: String, on: Boolean) = graph.setSwitch(identity, on)

    override suspend fun replaceCredential(profileId: String, result: SignInResult) = graph.replaceCredential(profileId, result)

    override suspend fun maskedKey(profileId: String): String? {
        val profile = graph.stored.value?.profiles?.firstOrNull { it.id == profileId } ?: return null
        return (profile.credentialId?.let { graph.credentials.read(it) } as? Credential.ApiKey)?.let { Entries.masked(it.key) }
    }

    override suspend fun models(profileId: String): List<ModelEntry> {
        val profile = graph.stored.value?.profiles?.firstOrNull { it.id == profileId } ?: return emptyList()
        return OfferedModels.rows(graph.catalog, profile.providerId, profile.choiceId, graph.models(profileId).models)
    }

    override suspend fun refreshModels(profileId: String): List<ModelEntry> = graph.refreshModels(profileId)

    override suspend fun allowance(profileId: String): List<CopilotQuota> = graph.allowance(profileId)

    override fun priceKnown(profile: Profile, modelId: String): Boolean {
        val name = graph.catalog.provider(profile.providerId)?.models?.firstOrNull { it.id == modelId }?.name
        return PriceTable(graph.catalog).priceFor(profile, modelId, name, LocalDate.now(ZoneOffset.UTC)).known
    }
}
