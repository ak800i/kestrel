package dev.kestrel.app.debug

import dev.kestrel.app.AppGraph
import dev.kestrel.app.KestrelApplication

/**
 * Kestrel's application in debug builds (`app\src\debug\AndroidManifest.xml`): at every start of the process, before
 * any of the graph's work, it starts the rule of the hooks' marks ([HookConversations.watch]), so that the owner's
 * words clear a mark that survived a restart also when no hook has been called in the process yet (review I2 of plan
 * 7F). Release builds have neither this class nor the rule.
 */
class DebugKestrelApplication : KestrelApplication() {
    override fun graphBuilt(graph: AppGraph) {
        HookConversations.watch(noBackupFilesDir, VoiceHooks.tags) { steps -> graph.conversationSteps = steps }
    }
}
