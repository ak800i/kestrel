package dev.kestrel.screen.read

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
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

    /**
     * Whether the windows of [live] and a few of its actionable elements are still where [live] saw them. The elements
     * are read again past the accessibility cache, because while a window slides into place, its nodes move without an
     * event, and the cache keeps the bounds of the first read.
     */
    fun inPlace(live: LiveSnapshot): Boolean {
        val snapshot = live.snapshot
        val windows = service.windows.associateBy { it.id }
        for (window in snapshot.windows) {
            val now = windows[window.id] ?: return false
            if (Rect().also(now::getBoundsInScreen).toIntRect() != window.bounds) return false
        }
        val actionable = snapshot.elements.filter { it.isActionable }
        val sample = listOfNotNull(actionable.firstOrNull(), actionable.getOrNull(actionable.size / 2), actionable.lastOrNull()).distinct()
        for (element in sample) {
            val node = live.node(element.key) ?: continue
            if (!node.refresh()) return false
            val window = snapshot.windows.firstOrNull { it.id == element.windowId } ?: return false
            if (Rect().also(node::getBoundsInScreen).toIntRect().intersect(window.bounds) != element.bounds) return false
        }
        return true
    }

    /** Clears the accessibility cache of Kestrel's service, so that the next [read] reads every node anew. */
    fun forget() {
        service.clearCache()
    }
}
