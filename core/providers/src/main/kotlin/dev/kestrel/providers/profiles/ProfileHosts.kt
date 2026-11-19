package dev.kestrel.providers.profiles

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.http.Hosts
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The one rule for a profile's base URL and for the hosts that a saved profile adds to the allowlist (design D13 and D16),
 * so that the allowlist and the requests cannot disagree.
 */
object ProfileHosts {
    private val REGION = Regex("^[a-z0-9-]+$")

    /**
     * The effective base URL: the profile's own base URL, else the choice's, else the base URL of the region that the
     * profile chose when the provider lists it, else the provider's; with `{region}` and `{location}` filled in from the
     * profile. Null when there is none or a placeholder stays empty.
     */
    fun baseUrl(profile: Profile, catalog: Catalog): HttpUrl? {
        val provider = catalog.provider(profile.providerId)
        val raw = profile.baseUrl
            ?: catalog.choice(profile.providerId, profile.choiceId)?.settings?.baseUrl
            ?: provider?.regions?.firstOrNull { it.id == profile.region }?.baseUrl
            ?: provider?.baseUrl
            ?: return null
        return fill(raw, profile.region)?.toHttpUrlOrNull()
    }

    /**
     * The public hosts that [profile] needs: its base URL's host; when neither the profile nor the choice sets a base URL,
     * the provider's hosts and its models' base URL hosts, with the region filled in; the choice's refresh hosts; and the
     * hosts that the sign-in and discovery accepted. Local hosts are left out, because they go to [localServers].
     */
    fun hosts(profile: Profile, catalog: Catalog): Set<String> {
        val provider = catalog.provider(profile.providerId)
        val choice = catalog.choice(profile.providerId, profile.choiceId)?.settings
        val hosts = sortedSetOf<String>()
        baseUrl(profile, catalog)?.let { hosts += it.host }
        if (provider != null && profile.baseUrl == null && choice?.baseUrl == null) {
            for (host in provider.hosts + provider.models.mapNotNull { CatalogLoader.hostOf(it.baseUrl) }) {
                fill(host, profile.region)?.let { hosts += it }
            }
        }
        choice?.refreshHosts?.forEach { hosts += it }
        profile.discoveredHosts.forEach { hosts += it }
        return hosts.mapNotNull(::canonicalHost).filterNot(Hosts::isLocal).toSortedSet()
    }

    /** The host of a profile whose own address is local, for NetworkPolicy.setLocalServers (design D11). */
    fun localServers(profile: Profile): Set<String> =
        profile.baseUrl?.toHttpUrlOrNull()?.host?.let(::canonicalHost)?.takeIf(Hosts::isLocal)?.let { setOf(it) } ?: emptySet()

    /**
     * [host] as NetworkPolicy keeps it: in OkHttp's form of a URL's host, without brackets or a trailing dot. Null for a
     * text that OkHttp refuses as a host, which no request can have: NetworkPolicy would refuse the whole update.
     */
    private fun canonicalHost(host: String): String? {
        val name = host.trim().removePrefix("[").removeSuffix("]").removeSuffix(".")
        return try {
            HttpUrl.Builder().scheme("https").host(name).build().host
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun fill(text: String, region: String?): String? {
        if ("{region}" !in text && "{location}" !in text) return text
        if (region == null || !REGION.matches(region)) return null
        return text.replace("{region}", region).replace("{location}", region)
    }
}
