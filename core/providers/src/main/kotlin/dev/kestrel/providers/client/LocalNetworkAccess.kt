package dev.kestrel.providers.client

import dev.kestrel.providers.http.Hosts

/** Whether Kestrel may reach a host on the owner's network now; `:app` asks Android for ACCESS_LOCAL_NETWORK (design D11). */
fun interface LocalNetworkAccess {
    fun allowed(host: String): Boolean
}

/** Which hosts need Android 17's local-network permission (design D11; prototype P5). */
object LocalNetwork {
    /** Prototype P5 found that loopback needs no permission, granted, revoked or undeclared (q3 and q7). */
    const val LOOPBACK_NEEDS_PERMISSION: Boolean = false

    /** Whether a request to [host] needs the permission: a host on the owner's network, and loopback only if it needed it. */
    fun needsPermission(host: String): Boolean =
        Hosts.isLocal(host) && (LOOPBACK_NEEDS_PERMISSION || !Hosts.isLoopback(host))
}
