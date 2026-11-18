package dev.kestrel.screen.read

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot

/** A snapshot whose elements still lead to their live accessibility nodes, which actions need. */
class LiveSnapshot internal constructor(
    val snapshot: ScreenSnapshot,
    private val nodes: Map<String, NodeData>,
    private val anchors: Map<Int, List<SnapshotBuilder.Anchor>> = emptyMap(),
) {
    /** The live node of the element with [key], or null when this snapshot has no such element. */
    fun node(key: String): AccessibilityNodeInfo? = (nodes[key] as? AccessibilityNodeData)?.info

    /**
     * Whether the windows in [moving] are still where this snapshot saw them (design spec, section 9.2): up to three
     * actionable elements of each such window (the first, the middle and the last), or, in a window without one, its
     * [SnapshotBuilder.Anchor]s. A window that this snapshot does not have counts as in place. [fresh] reads a node's
     * bounds again, past any cache, or gives null when the node is gone.
     */
    internal fun inPlace(moving: Set<Int>, fresh: (NodeData) -> IntRect?): Boolean {
        for (window in snapshot.windows) {
            if (window.id !in moving) continue
            val actionable = snapshot.elements.filter { it.windowId == window.id && it.isActionable }
            val sample = if (actionable.isEmpty()) {
                anchors[window.id].orEmpty().map { it.node to it.bounds }
            } else {
                listOfNotNull(actionable.first(), actionable[actionable.size / 2], actionable.last()).distinct()
                    .mapNotNull { element -> nodes[element.key]?.let { it to element.bounds } }
            }
            for ((node, bounds) in sample) {
                if (fresh(node)?.intersect(window.bounds) != bounds) return false
            }
        }
        return true
    }
}

/** Reads every interactive window that Kestrel's accessibility service can see (design spec, section 9.2). */
class ScreenReader(private val service: AccessibilityService, private val builder: SnapshotBuilder = SnapshotBuilder()) {
    fun read(): LiveSnapshot {
        val result = builder.build(service.windows.map(::AccessibilityWindowData))
        return LiveSnapshot(result.snapshot, result.nodes, result.anchors)
    }

    /**
     * Whether the windows in [moving] that [live] has are still where [live] saw them. Each such window must still be in
     * Android's window list, as `AccessibilityService.getWindows()` gives it, which Android may serve from its cache,
     * with the same bounds. A few of the window's nodes are read again past the accessibility cache
     * ([LiveSnapshot.inPlace]), because while a window slides into place, its nodes move without an event, and the cache
     * keeps the bounds of the first read.
     */
    fun inPlace(live: LiveSnapshot, moving: Set<Int>): Boolean {
        val windows = service.windows.associateBy { it.id }
        for (window in live.snapshot.windows) {
            if (window.id !in moving) continue
            val now = windows[window.id] ?: return false
            if (Rect().also(now::getBoundsInScreen).toIntRect() != window.bounds) return false
        }
        return live.inPlace(moving) { node ->
            val info = (node as? AccessibilityNodeData)?.info
            if (info == null || !info.refresh()) null else Rect().also(info::getBoundsInScreen).toIntRect()
        }
    }

    /** Clears the accessibility cache of Kestrel's service, so that the next [read] reads every node anew. */
    fun forget() {
        service.clearCache()
    }
}
