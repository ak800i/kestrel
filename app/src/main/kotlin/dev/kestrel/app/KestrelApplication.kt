package dev.kestrel.app

import android.app.Application
import android.content.Context
import dev.kestrel.app.conversation.processEndOf
import dev.kestrel.privacy.logs.MainThreadErrors
import dev.kestrel.privacy.logs.MessageFreeCrashHandler
import kotlinx.coroutines.launch

open class KestrelApplication : Application() {
    lateinit var graph: AppGraph
        private set

    // Here rather than in onCreate(), because Android starts the app's content providers between the two.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Thread.getDefaultUncaughtExceptionHandler()?.let { Thread.setDefaultUncaughtExceptionHandler(MessageFreeCrashHandler(it)) }
        MainThreadErrors.install()
    }

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graphBuilt(graph)
        // Each from the one reading of how the last process ended, and each on its own, so that a failed restore never
        // takes the accessibility notice with it.
        graph.previousExit.launchEach(
            graph.scope,
            { exit -> graph.unboundServiceCheck.afterStart(exit) },
            { exit -> graph.conversations.restore(processEndOf(exit)) },
        )
        graph.scope.launch { graph.logs.purgeExpired() }
        graph.scope.launch { graph.keepPurgingConversations() }
        // Kestrel creates the folder for its models itself: on Android 17 it can read a folder that adb creates only
        // after a chmod (design spec, section 8.10).
        graph.scope.launch { graph.models.root.mkdirs() }
    }

    /**
     * Runs once in each process, after [graph] is built, before the conversation's first step and before onCreate starts
     * any work, so that what it installs sees every step of the conversation. The debug builds' application installs its hooks' process-wide rules here
     * (`DebugKestrelApplication`); a release build installs nothing.
     */
    protected open fun graphBuilt(graph: AppGraph) = Unit
}
