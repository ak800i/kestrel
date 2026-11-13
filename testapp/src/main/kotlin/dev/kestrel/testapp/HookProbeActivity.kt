package dev.kestrel.testapp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Sends a test-hook command to Kestrel's debug build the way any other app could, and shows whether Kestrel
 * answered. Kestrel's receiver requires android.permission.DUMP, so it must not answer (design spec, section 16.1).
 */
class HookProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = fixturePage("Hook probe").addStatus()
        val command = Intent("dev.kestrel.hook.STATE").setClassName("dev.kestrel", "dev.kestrel.app.debug.TestHookReceiver")
        val answer = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                status.text = if (resultData == null) "Probe refused" else "Probe answered"
            }
        }
        sendOrderedBroadcast(command, null, answer, null, RESULT_CANCELED, null, null)
    }
}
