package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import okhttp3.HttpUrl

/** Where a profile's model list comes from (design D13). It runs only when a profile is created or the owner taps "Refresh models". */
object Discoveries {
    /** The list of a local server of [kind] at [baseUrl], whose paths start at the address's origin (design D11). */
    fun forLocalServer(kind: LocalServerKind, baseUrl: HttpUrl, auth: RequestAuth, http: ProviderHttp): ModelDiscovery =
        LocalServerDiscovery(kind, baseUrl, auth, http)
}
