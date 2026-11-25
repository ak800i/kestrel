package dev.kestrel.app.phone

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

/**
 * Android's screen and lock (design spec, section 8.5), as ListeningService read them until plan 7D: Kestrel counts the
 * phone as unlocked after USER_PRESENT until the screen goes off, because KeyguardManager can learn of the unlock a moment
 * later, and a SCREEN_ON that Kestrel handles after USER_PRESENT must not report the old lock. Android calls the receiver
 * on the main thread.
 */
internal class AndroidScreenSource(private val context: Context) : ScreenSource {
    // The owner unlocked the phone since the screen last went off (USER_PRESENT).
    @Volatile
    private var unlocked = false
    private var receiver: BroadcastReceiver? = null

    override fun start(report: (Boolean) -> Unit, cameOn: () -> Unit) {
        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> unlocked = true
                    Intent.ACTION_SCREEN_OFF -> unlocked = false
                }
                report(current())
                if (intent.action == Intent.ACTION_SCREEN_ON) cameOn()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        synchronized(this) {
            receiver?.let(context::unregisterReceiver)
            unlocked = false
            // SCREEN_ON and SCREEN_OFF come from the system, but USER_PRESENT comes from SystemUI, and Android gives another
            // app's broadcasts only to an exported receiver. All three are protected broadcasts, which other apps cannot
            // send.
            context.registerReceiver(screen, filter, Context.RECEIVER_EXPORTED)
            receiver = screen
        }
    }

    override fun stop() {
        synchronized(this) {
            receiver?.let(context::unregisterReceiver)
            receiver = null
        }
    }

    override fun current(): Boolean {
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        return interactive && (unlocked || !locked)
    }

    override fun toString(): String = "AndroidScreenSource(watching=${receiver != null})"
}
