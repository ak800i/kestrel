package dev.kestrel.app

import android.app.Application
import kotlinx.coroutines.launch

class KestrelApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch { graph.logs.purgeExpired() }
    }
}
