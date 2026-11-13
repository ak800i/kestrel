package dev.kestrel.screen.overlay

import dev.kestrel.screen.model.ScreenElement

/** Names next to elements: their labels, or names derived from their view IDs (design spec, section 9.4). */
internal class LabelsOverlay(private val host: OverlayHost) {
    private val view = TagsView(host.context).apply { fillColor = LABEL_COLOR }

    /** Whether the labels are on the screen now. */
    @Volatile
    var isShowing: Boolean = false
        private set

    fun show(elements: List<ScreenElement>) {
        view.tags = elements.mapNotNull { element -> (element.label ?: element.suggestedName)?.let { Tag(it, element.bounds) } }
        host.show(view, OverlayHost.fullScreen("Kestrel labels"))
        isShowing = true
    }

    fun hide() {
        isShowing = false
        host.hide(view)
    }

    private companion object {
        const val LABEL_COLOR = 0xE6424242.toInt()
    }
}
