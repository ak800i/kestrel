package dev.kestrel.screen.read

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import dev.kestrel.screen.model.ScreenSnapshot

/** A snapshot whose elements still lead to their live accessibility nodes, which actions need. */
class LiveSnapshot internal constructor(val snapshot: ScreenSnapshot, private val nodes: Map<String, NodeData>) {
    /** The live node of the element with [key], or null when this snapshot has no such element. */
    fun node(key: String): AccessibilityNodeInfo? = (nodes[key] as? AccessibilityNodeData)?.info
}

/** Reads every interactive window that Kestrel's accessibility service can see (design spec, section 9.2). */
class ScreenReader(private val service: AccessibilityService, private val builder: SnapshotBuilder = SnapshotBuilder()) {
    fun read(): LiveSnapshot {
        val result = builder.build(service.windows.map(::AccessibilityWindowData))
        return LiveSnapshot(result.snapshot, result.nodes)
    }
}
