package dev.kestrel.app.providers

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.profiles.ProfileHosts
import dev.kestrel.providers.profiles.ProvidersSetting

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
}
