package dev.kestrel.testapp

import android.app.Activity
import android.net.Uri
import android.os.Bundle

/**
 * Calls a test hook of Kestrel's debug build the way any other app could, through the hooks' provider, and shows whether
 * Kestrel answered. The provider requires android.permission.DUMP, so it must refuse (design spec, section 16.1); a
 * missing provider is no refusal, so it shows apart.
 */
class HookProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = fixturePage("Hook probe").addStatus()
        status.text = try {
            contentResolver.call(Uri.parse("content://dev.kestrel.hooks"), "STATE", null, null)
            "Probe answered"
        } catch (e: SecurityException) {
            "Probe refused"
        } catch (e: IllegalArgumentException) {
            "Probe found no provider"
        }
    }
}
