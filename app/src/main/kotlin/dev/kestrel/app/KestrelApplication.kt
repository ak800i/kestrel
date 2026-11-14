package dev.kestrel.app

import android.app.Application
import dev.kestrel.privacy.logs.MessageFreeCrashHandler
import kotlinx.coroutines.launch

class KestrelApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        Thread.getDefaultUncaughtExceptionHandler()?.let { Thread.setDefaultUncaughtExceptionHandler(MessageFreeCrashHandler(it)) }
        graph = AppGraph(this)
        graph.scope.launch { graph.logs.purgeExpired() }
    }
}
