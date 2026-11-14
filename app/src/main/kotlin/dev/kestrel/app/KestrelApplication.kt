package dev.kestrel.app

import android.app.Application
import android.content.Context
import dev.kestrel.privacy.logs.MainThreadErrors
import dev.kestrel.privacy.logs.MessageFreeCrashHandler
import kotlinx.coroutines.launch

class KestrelApplication : Application() {
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
        graph.scope.launch { graph.logs.purgeExpired() }
    }
}
