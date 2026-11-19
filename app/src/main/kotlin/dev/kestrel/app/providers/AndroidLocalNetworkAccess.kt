package dev.kestrel.app.providers

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.kestrel.providers.client.LocalNetworkAccess

/**
 * Whether Kestrel may reach a server on the owner's network (design D11): from Android 17 on, an app that targets API 37
 * needs ACCESS_LOCAL_NETWORK for it; earlier Android versions, such as the A71's Android 13, have no such permission.
 */
internal class AndroidLocalNetworkAccess(private val context: Context) : LocalNetworkAccess {
    override fun allowed(host: String): Boolean =
        Build.VERSION.SDK_INT < LOCAL_NETWORK_API || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

        /** Android 17, whose local-network permission applies to apps that target it (research r40). */
        const val LOCAL_NETWORK_API = 37
    }
}
