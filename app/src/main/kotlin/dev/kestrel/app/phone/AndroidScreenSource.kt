package dev.kestrel.app.phone

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/**
 * Android's screen and lock (design spec, section 8.5): the broadcasts SCREEN_OFF, SCREEN_ON and USER_PRESENT, and the
 * screen's and the keyguard's state when the source reads, which [ScreenReadings] turns into the source's readings, so
 * that each reading is correct at the moment it is read (plan 7F's fourth re-check). The source's thread is the main
 * thread: the receiver runs there, and so does every reading, so that a reading and the broadcasts keep their order.
 * When a look at the screen, at a tap's stamp or while the keyguard settles after USER_PRESENT, finds another reading
 * than the newest that the source gave, as when the lock screen came back without a screen-off (Lockdown) or the keyguard
 * caught up with the unlock late, the source reports it as a new reading. [keyguardCaughtUp] hears how long after
 * USER_PRESENT the keyguard read unlocked, which the app graph logs (Task 14 measures it).
 */
internal class AndroidScreenSource(
    private val context: Context,
    keyguardCaughtUp: (millis: Long) -> Unit = {},
) : ScreenSource {
    private val main = Handler(Looper.getMainLooper())
    private val readings = ScreenReadings(
        interactive = { context.getSystemService(PowerManager::class.java).isInteractive },
        keyguardLocked = { context.getSystemService(KeyguardManager::class.java).isKeyguardLocked },
        clock = SystemClock::uptimeMillis,
        onSourceThread = { main.looper.isCurrentThread },
        caughtUp = keyguardCaughtUp,
    )

    // While the keyguard settles after USER_PRESENT, the source looks again at each poll.
    private val settle = object : Runnable {
        override fun run() {
            confirm()
            if (readings.settling()) main.postDelayed(this, ScreenReadings.KEYGUARD_POLL_MILLIS)
        }
    }
    private var receiver: BroadcastReceiver? = null

    // Where the readings go while the source runs: set under this object's lock, read on the main thread.
    @Volatile
    private var report: ((ScreenStatus) -> Unit)? = null

    override fun start(report: (ScreenStatus) -> Unit, cameOn: () -> Unit) {
        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val broadcast = when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> ScreenReadings.Broadcast.USER_PRESENT
                    Intent.ACTION_SCREEN_OFF -> ScreenReadings.Broadcast.SCREEN_OFF
                    else -> ScreenReadings.Broadcast.SCREEN_ON
                }
                report(readings.heard(broadcast))
                when (broadcast) {
                    ScreenReadings.Broadcast.SCREEN_ON -> cameOn()
                    ScreenReadings.Broadcast.USER_PRESENT -> {
                        main.removeCallbacks(settle)
                        if (readings.settling()) main.postDelayed(settle, ScreenReadings.KEYGUARD_POLL_MILLIS)
                    }
                    ScreenReadings.Broadcast.SCREEN_OFF -> main.removeCallbacks(settle)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        synchronized(this) {
            receiver?.let(context::unregisterReceiver)
            main.removeCallbacks(settle)
            this.report = report
            // SCREEN_ON and SCREEN_OFF come from the system, but USER_PRESENT comes from SystemUI, and Android gives another
            // app's broadcasts only to an exported receiver. All three are protected broadcasts, which other apps cannot
            // send.
            context.registerReceiver(screen, filter, null, main, Context.RECEIVER_EXPORTED)
            receiver = screen
        }
    }

    override fun stop() {
        synchronized(this) {
            receiver?.let(context::unregisterReceiver)
            main.removeCallbacks(settle)
            receiver = null
            report = null
        }
    }

    // The tap's stamp reads the screen now, on the main thread only; a reading that differs from the newest given is
    // reported after it.
    override fun status(): ScreenStatus = readings.status().also { main.post(::confirm) }

    override fun read(reading: (ScreenStatus) -> Unit) {
        if (main.looper.isCurrentThread) reading(readings.next()) else main.post { reading(readings.next()) }
    }

    // On the main thread: reports the screen as it reads now when that differs from the newest reading given.
    private fun confirm() {
        val report = report ?: return
        if (readings.stale()) report(readings.next())
    }

    override fun toString(): String = "AndroidScreenSource(watching=${receiver != null})"
}
