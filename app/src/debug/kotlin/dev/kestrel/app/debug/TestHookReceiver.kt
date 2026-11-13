package dev.kestrel.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.kestrel.app.KestrelApplication
import dev.kestrel.privacy.logs.describeWithoutMessages
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Receives test-hook commands from adb, in debug builds only (design spec, section 16.1). Android delivers them only
 * from callers that hold `android.permission.DUMP`, which in practice means `adb shell`; `scripts/hook.ps1` sends
 * them. The answer is the broadcast's result code and data, see [HookReply].
 */
class TestHookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as KestrelApplication).graph
        val pending = goAsync()
        graph.scope.launch {
            val reply = try {
                withTimeout(TIMEOUT_MILLIS) { TestHooks(graph).run(intent) }
            } catch (error: Exception) {
                HookReply.failed("the command failed: ${error.describeWithoutMessages()}")
            }
            pending.setResult(reply.code, reply.text, null)
            pending.finish()
        }
    }

    private companion object {
        // Android waits 60 s for a background broadcast, and an action waits at most 3 s for the screen to settle.
        const val TIMEOUT_MILLIS = 20_000L
    }
}
