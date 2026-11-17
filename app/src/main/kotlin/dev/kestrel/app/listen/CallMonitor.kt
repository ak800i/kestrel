package dev.kestrel.app.listen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import dev.kestrel.app.orchestrator.CallState
import java.util.concurrent.Executor

/** Tells Kestrel the phone's call state (design spec, section 8.7). Any thread may call it. */
internal interface CallMonitor {
    /**
     * Starts to report the call state to [report]: the state at the start, and then every change. Does nothing without
     * the permission READ_PHONE_STATE, so that Kestrel never enters Ringing then. [report] is called on a thread of
     * Android's and must return at once. A start replaces the reports of an earlier one.
     */
    fun start(report: (CallState) -> Unit)

    /** Stops the reports. A report that is on its way may still come. */
    fun stop()
}

/**
 * The call state from Android's call-state listener, which reports a ringing call, an answer and the end of a call
 * within about a second (prototype P3). It reads no phone number and no call log. Revoking READ_PHONE_STATE ends
 * Kestrel's process, so the listener never outlives the permission.
 */
internal class AndroidCallMonitor(private val context: Context) : CallMonitor {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val lock = Any()
    private var listener: TelephonyCallback? = null

    override fun start(report: (CallState) -> Unit) {
        synchronized(lock) {
            stopLocked()
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    report(callStateOf(state))
                }
            }
            // Android's binder thread reports at once; the report only queues an event.
            telephony.registerTelephonyCallback(Executor { command -> command.run() }, callback)
            listener = callback
        }
    }

    override fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        listener?.let { telephony.unregisterTelephonyCallback(it) }
        listener = null
    }
}

/** Android's call state, one of TelephonyManager's `CALL_STATE_` values, as Kestrel's [CallState]. */
internal fun callStateOf(state: Int): CallState = when (state) {
    TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
    TelephonyManager.CALL_STATE_OFFHOOK -> CallState.ACTIVE
    else -> CallState.IDLE
}
