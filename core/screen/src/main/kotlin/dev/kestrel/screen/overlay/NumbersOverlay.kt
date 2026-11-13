package dev.kestrel.screen.overlay

/** Numbers on elements, for "tap 5" (design spec, section 9.4). Use it on the main thread. */
internal class NumbersOverlay(private val host: OverlayHost) {
    private val view = TagsView(host.context).apply { fillColor = NUMBER_COLOR }

    /** The numbers on the screen now; empty while the overlay is hidden. */
    var badges: List<Badge> = emptyList()
        private set

    fun show(badges: List<Badge>) {
        this.badges = badges
        view.tags = badges.map { Tag(it.number.toString(), it.bounds) }
        host.show(view, OverlayHost.fullScreen("Kestrel numbers"))
    }

    fun hide() {
        badges = emptyList()
        host.hide(view)
    }

    private companion object {
        const val NUMBER_COLOR = 0xE61565C0.toInt()
    }
}
