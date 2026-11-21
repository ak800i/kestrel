package dev.kestrel.screen.model

import kotlinx.serialization.Serializable

/** One kind of element of a screen signature and how often it occurs: no screen text. */
@Serializable
data class SignaturePart(val className: String?, val viewId: String?, val region: Region, val count: Int)

/**
 * A screen's shape without its text (design D6 of sub-project 5): the front app's package, its active window's ID and
 * kind, and the multiset of (class, view-ID name, region) of the elements outside every scrollable container, with the
 * regions measured above the keyboard ([ScreenTree.visibleArea]). Routines store it, so it holds no label.
 */
@Serializable
data class Signature(val packageName: String?, val windowId: Int?, val windowKind: WindowKind?, val parts: List<SignaturePart>) {
    /** The weighted Jaccard similarity of the two multisets: the counts matter, not only which parts occur. */
    fun similarity(other: Signature): Double {
        val mine = parts.associate { Triple(it.className, it.viewId, it.region) to it.count }
        val theirs = other.parts.associate { Triple(it.className, it.viewId, it.region) to it.count }
        val keys = mine.keys + theirs.keys
        if (keys.isEmpty()) return 1.0
        val shared = keys.sumOf { minOf(mine[it] ?: 0, theirs[it] ?: 0) }
        val all = keys.sumOf { maxOf(mine[it] ?: 0, theirs[it] ?: 0) }
        return shared.toDouble() / all
    }

    /**
     * The same app and window kind, and parts at least [ScreenSignature.SAME] alike. The window's ID does not count:
     * Android gives every window instance a new one, also the same screen after its app's process restarted, so a routine
     * recorded in an earlier session could never match (design D23). Within one step a new window is another screen
     * (design D6), so the step's own comparisons (`Changes`, the observation's stars) compare [windowId] as well.
     */
    fun sameScreen(other: Signature): Boolean =
        packageName == other.packageName && windowKind == other.windowKind && similarity(other) + 1e-9 >= ScreenSignature.SAME

    override fun toString(): String = "Signature(packageLength=${packageName?.length ?: 0}, kind=$windowKind, parts=${parts.size})"
}

object ScreenSignature {
    /** Two signatures at least this alike are the same screen (design D6 of sub-project 5). */
    const val SAME = 0.8

    fun of(snapshot: ScreenSnapshot): Signature {
        val window = ScreenTree.frontWindow(snapshot) ?: return Signature(null, null, null, emptyList())
        val area = ScreenTree.visibleArea(snapshot, window)
        val counts = snapshot.elements.indices
            .filter { snapshot.elements[it].windowId == window.id && !ScreenTree.insideScrollable(snapshot, it) }
            .map { snapshot.elements[it] }
            .groupingBy { Triple(it.className, ScreenTree.idName(it.viewId), Region.of(it.bounds, area)) }
            .eachCount()
        val parts = counts.map { (key, count) -> SignaturePart(key.first, key.second, key.third, count) }
            .sortedWith(compareBy({ it.region.ordinal }, { it.className.orEmpty() }, { it.viewId.orEmpty() }))
        return Signature(window.packageName, window.id, window.kind, parts)
    }
}
