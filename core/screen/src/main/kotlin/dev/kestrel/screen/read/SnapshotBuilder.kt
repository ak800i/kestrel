package dev.kestrel.screen.read

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowSummary

/** Builds a [ScreenSnapshot] from the windows on the screen (design spec, section 9.2). */
class SnapshotBuilder(private val clock: () -> Long = System::currentTimeMillis) {
    /** A snapshot, together with the node behind each element, so that actions can use the live nodes. */
    class Result(val snapshot: ScreenSnapshot, val nodes: Map<String, NodeData>)

    fun build(windows: List<WindowData>): Result {
        val drafts = mutableListOf<Draft>()
        val summaries = mutableListOf<WindowSummary>()
        for (window in windows.filterNot { it.isOverlay }.sortedByDescending { it.layer }) {
            val root = window.root
            summaries += WindowSummary(
                id = window.id,
                kind = window.kind,
                packageName = root?.packageName,
                title = window.title,
                bounds = window.bounds,
                layer = window.layer,
                active = window.active,
                focused = window.focused,
            )
            if (root != null) visit(root, window, depth = 0, parent = null, out = drafts)
        }
        val keys = Keys()
        val elements = drafts.map { it.toElement(keys.next(it)) }
        val nodes = elements.indices.associate { elements[it].key to drafts[it].node }
        return Result(ScreenSnapshot(clock(), summaries, elements), nodes)
    }

    /** Adds the elements of [node]'s subtree to [out], and returns up to three texts from the subtree, its own first. */
    private fun visit(node: NodeData, window: WindowData, depth: Int, parent: Int?, out: MutableList<Draft>): List<String> {
        if (!node.visible || depth > MAX_DEPTH || out.size >= MAX_ELEMENTS) return emptyList()
        val text = if (node.password) null else Labels.clean(node.text)
        val name = Labels.clean(node.contentDescription) ?: Labels.clean(node.hint)
        // A text field is named by its description or hint, never by what was typed into it.
        val ownLabel = if (node.editable) name else text ?: name
        val bounds = node.bounds.intersect(window.bounds)
        val actionable = node.clickable || node.longClickable || node.editable || node.checkable
        val described = ownLabel != null || Labels.clean(node.stateDescription) != null
        val index = if (bounds != null && (actionable || node.scrollable || described)) {
            out += Draft(node, window.id, parent, bounds, text, ownLabel)
            out.lastIndex
        } else {
            null
        }
        val pieces = mutableListOf<String>()
        ownLabel?.let(pieces::add)
        for (i in 0 until node.childCount) {
            val child = node.child(i) ?: continue
            for (piece in visit(child, window, depth + 1, index ?: parent, out)) {
                if (pieces.size < MAX_LABEL_PIECES) pieces += piece
            }
        }
        // A tappable row without text of its own is named after the text inside it, as in Voice Access.
        if (index != null && ownLabel == null && actionable) out[index].innerLabel = Labels.join(pieces)
        return pieces
    }

    private class Draft(
        val node: NodeData,
        val windowId: Int,
        val parent: Int?,
        val bounds: IntRect,
        val text: String?,
        val ownLabel: String?,
    ) {
        var innerLabel: String? = null
        val label: String? get() = ownLabel ?: innerLabel

        fun toElement(key: String) = ScreenElement(
            key = key,
            windowId = windowId,
            parent = parent,
            packageName = node.packageName,
            className = node.className,
            text = text,
            contentDescription = Labels.clean(node.contentDescription),
            hint = Labels.clean(node.hint),
            stateDescription = Labels.clean(node.stateDescription),
            viewId = node.viewId,
            label = label,
            suggestedName = if (label == null) Labels.suggestedName(node.viewId) else null,
            bounds = bounds,
            actions = node.actions,
            enabled = node.enabled,
            clickable = node.clickable,
            longClickable = node.longClickable,
            editable = node.editable,
            checkable = node.checkable,
            checked = node.checked,
            scrollable = node.scrollable,
            focused = node.focused,
            selected = node.selected,
            password = node.password,
            dataSensitive = node.dataSensitive,
        )
    }

    /**
     * Builds keys of the form `package|viewId|label|class|position` from the element's package, the part of its view
     * ID after `:id/`, its label cut to 40 characters and its class name without the package. Look-alikes are elements
     * whose first four parts are the same, and the position counts the look-alikes before the element under the same
     * parent in the same window. Look-alikes under different parents, such as the "Delete" buttons of a list's rows,
     * can get the same key, so a key that is already taken gets the suffix `~n`, where n counts the elements with that
     * key across the whole snapshot: the second one gets `~2`, the third `~3`, and so on.
     */
    private class Keys {
        private val siblings = mutableMapOf<Triple<Int, Int?, String>, Int>()
        private val used = mutableMapOf<String, Int>()

        fun next(draft: Draft): String {
            // A text field's key uses only its own description or hint, because the texts inside a field, such as
            // a placeholder or a character counter, can change while the owner types.
            val name = if (draft.node.editable) draft.ownLabel else draft.label
            val base = listOf(
                draft.node.packageName.orEmpty(),
                draft.node.viewId?.substringAfter(":id/").orEmpty(),
                name?.let { Labels.shorten(it, KEY_LABEL_LENGTH) }.orEmpty(),
                draft.node.className?.substringAfterLast('.').orEmpty(),
            ).joinToString("|")
            val group = Triple(draft.windowId, draft.parent, base)
            val position = siblings.getOrDefault(group, 0)
            siblings[group] = position + 1
            val key = "$base|$position"
            // Similar elements under different parents would get the same key, so later ones get a suffix.
            val seen = used.getOrDefault(key, 0) + 1
            used[key] = seen
            return if (seen == 1) key else "$key~$seen"
        }
    }

    private companion object {
        const val MAX_DEPTH = 100
        const val MAX_ELEMENTS = 2_000
        const val MAX_LABEL_PIECES = 3
        const val KEY_LABEL_LENGTH = 40
    }
}
