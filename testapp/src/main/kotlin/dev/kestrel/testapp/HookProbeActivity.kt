package dev.kestrel.testapp

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.RemoteException

/**
 * Calls a test hook of Kestrel's debug build the way any other app could, through binder to the hooks' provider, and
 * shows how far it got (design spec, section 16.1). The provider requires android.permission.DUMP for its acquisition,
 * so this app is refused there ("Probe refused at the acquisition"); after `pm grant` of DUMP it acquires the provider,
 * and the provider's check of the caller's uid refuses its call ("Probe refused on the call"). A missing provider is no
 * refusal, so it shows apart.
 */
class HookProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixturePage("Hook probe").addStatus().text = probe()
    }

    private fun probe(): String {
        val client = try {
            contentResolver.acquireUnstableContentProviderClient(HOOKS)
        } catch (e: SecurityException) {
            return "Probe refused at the acquisition"
        } ?: return "Probe found no provider"
        return client.use {
            try {
                it.call("STATE", null, null)
                "Probe answered"
            } catch (e: SecurityException) {
                "Probe refused on the call"
            } catch (e: RemoteException) {
                "Probe lost its call"
            }
        }
    }

    private companion object {
        val HOOKS: Uri = Uri.parse("content://dev.kestrel.hooks")
    }
}