package dev.kestrel.app.debug

import android.os.SystemClock
import dev.kestrel.app.AppGraph
import dev.kestrel.app.KestrelApplication

/**
 * Kestrel's application in debug builds (`app\src\debug\AndroidManifest.xml`): at every start of the process, before
 * any of the graph's work, it starts the rule of the hooks' marks ([HookConversations.watch]), so that the owner's
 * acts clear a mark that survived a restart also when no hook has been called in the process yet (review I2 of plan
 * 7F), and the hooks' claims of their presses of the goal notification's buttons ([ButtonClaims.start]), so that the
 * press of `goal-control send-later`, whose alarm may start a new process, is the hooks' there too (plan 7G), in this
 * boot of the phone only (`Settings.Global.BOOT_COUNT`, which Android counts up at every boot). A phone without a boot
 * count starts as usual: its claims then stay in one process, and send-later refuses ([ButtonClaims.bootCount]). Release
 * builds have neither this class nor the rule.
 */
class DebugKestrelApplication : KestrelApplication() {
    override fun graphBuilt(graph: AppGraph) {
        HookConversations.watch(noBackupFilesDir, VoiceHooks.tags) { steps -> graph.conversationSteps = steps }
        ButtonClaims.start(noBackupFilesDir, VoiceHooks.tags, SystemClock::elapsedRealtime, ButtonClaims.bootCount(contentResolver)) { acts ->
            graph.buttonActs = acts
        }
    }
}
