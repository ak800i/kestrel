package dev.kestrel.app.providers

import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.profiles.ProfileHosts
import dev.kestrel.providers.profiles.ProvidersSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/**
 * What the stored profiles add to the network allowlist (design spec, section 13.1): the public hosts of every profile
 * whose borrowed sign-in is not switched off, and the local servers, which alone may use plain HTTP. Hosts of providers
 * without a profile are never on it.
 */
internal object ProviderAllowlist {
    fun profileHosts(setting: ProvidersSetting, catalog: Catalog): Set<String> =
        setting.profiles.filter { setting.usable(it, catalog) }.flatMapTo(sortedSetOf()) { ProfileHosts.hosts(it, catalog) }

    fun localServers(setting: ProvidersSetting): Set<String> =
        setting.profiles.flatMapTo(sortedSetOf()) { ProfileHosts.localServers(it) }

    /**
     * Puts the hosts of [setting] on [policy]. Both lists are worked out before either is set, and the local servers go
     * first, because [policy] refuses a host that is not local; ProfileHosts already gives the profile hosts in the form
     * that [policy] accepts. So an update that throws, for example because the catalog cannot be read, leaves both lists as
     * they were.
     */
    fun apply(setting: ProvidersSetting, catalog: () -> Catalog, policy: NetworkPolicy) {
        val hosts = profileHosts(setting, catalog())
        policy.setLocalServers(localServers(setting))
        policy.setProfileHosts(hosts)
    }

    /**
     * The allowlist job (design spec, section 13.1): applies each stored [settings] to [policy] and then [publish]es it. It
     * runs until it is cancelled: a failed update (any exception but a cancellation) goes to [report] by its classes and the
     * next change tries again, and a failed read of the setting goes to [report] too and the setting is read again after
     * [retryMillis], so that the allowlist never stops following the profiles while Kestrel runs.
     */
    suspend fun keep(
        settings: Flow<ProvidersSetting>,
        catalog: () -> Catalog,
        policy: NetworkPolicy,
        publish: (ProvidersSetting) -> Unit,
        report: (String, Throwable) -> Unit,
        retryMillis: Long = READ_RETRY_MILLIS,
    ) {
        settings
            .retryWhen { cause, _ ->
                if (cause is CancellationException) return@retryWhen false
                report("Reading the provider setting", cause)
                delay(retryMillis)
                true
            }
            .collect { setting ->
                try {
                    apply(setting, catalog, policy)
                    publish(setting)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report("Updating the allowlist", e)
                }
            }
    }

    private const val READ_RETRY_MILLIS = 5_000L
}
