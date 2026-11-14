package dev.kestrel.app.debug.spike

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.startup.Initializer
import dev.kestrel.app.KestrelApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs when Kestrel's process starts. If the hook asked for it, Kestrel starts a recording 15 s after its accessibility
 * service connects, as it would after a reboot (spike S1).
 */
class SpikeStartup : Initializer<Unit> {
    override fun create(context: Context) {
        val app = context.applicationContext
        if (!SpikeListening.takeListenAtNextStart(app)) return
        // The app's graph exists only after Application.onCreate(), which Android calls after the initializers.
        Handler(Looper.getMainLooper()).post {
            val graph = (app as KestrelApplication).graph
            graph.scope.launch {
                val session = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { graph.screen.first { it != null } }
                if (session == null) {
                    SpikeListening.record(app, "listen source=boot start=service-not-connected")
                } else {
                    delay(START_DELAY_MILLIS)
                    SpikeListening.start(app, source = "boot", seconds = 3)
                }
            }
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 180_000L
        const val START_DELAY_MILLIS = 15_000L
    }
}
