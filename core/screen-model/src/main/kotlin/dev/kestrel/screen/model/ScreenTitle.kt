package dev.kestrel.screen.model

/**
 * The title of a screen (design D8 of sub-project 5). Android's window title is usually the activity's or the app's
 * label ("Telegram" on every chat), so it comes last. The rules, in order:
 * 1. the app's rule in [ScreenTitles];
 * 2. a text in the window's toolbar or action bar, or an element whose view ID holds "title" in the top band of the
 *    window outside every scrollable container (a chat header, never a list row such as `android:id/title`);
 * 3. with `withRule3`, the first text without an action in the top band, which can be a list's first row or a clock, so
 *    a recipient (design D14) and "another screen" (design D6) leave it out;
 * 4. the window's title, only when it differs from the app's launcher label, and only when the caller knows that label.
 * No title is a valid answer.
 */
object ScreenTitle {
    /** The top part of the window in which rules 2 and 3 look: 15 % of its height. */
    const val TOP_BAND = 0.15

    fun of(snapshot: ScreenSnapshot, window: WindowSummary, appLabel: String?, withRule3: Boolean = true): String? {
        val elements = snapshot.elements
        val inWindow = elements.indices.filter { elements[it].windowId == window.id }
            .sortedWith(compareBy({ elements[it].bounds.top }, { elements[it].bounds.left }))
        ScreenTitles.ruleFor(window.packageName)?.let { rule -> byRule(snapshot, window, rule, inWindow)?.let { return it } }
        val band = window.bounds.top + window.bounds.height * TOP_BAND
        fun inBand(element: ScreenElement) = element.bounds.centerY < band
        inWindow.firstOrNull { index ->
            val element = elements[index]
            val titleId = ScreenTree.idName(element.viewId)?.contains("title", ignoreCase = true) == true
            text(element) != null && !element.editable &&
                (ScreenTree.inToolbar(snapshot, index) || (titleId && inBand(element) && !ScreenTree.insideScrollable(snapshot, index)))
        }?.let { return text(elements[it]) }
        if (withRule3) {
            inWindow.firstOrNull { index ->
                val element = elements[index]
                text(element) != null && !element.isActionable && !element.editable && inBand(element)
            }?.let { return text(elements[it]) }
        }
        val title = window.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (appLabel == null || title.equals(appLabel.trim(), ignoreCase = true)) return null
        return title
    }

    private fun byRule(snapshot: ScreenSnapshot, window: WindowSummary, rule: TitleRule, inWindow: List<Int>): String? {
        val elements = snapshot.elements
        for (id in rule.titleIds) {
            inWindow.firstOrNull { ScreenTree.idName(elements[it].viewId) == id }?.let { index -> named(elements[index])?.let { return it } }
        }
        val fraction = rule.headerBand ?: return null
        val bottom = window.bounds.top + window.bounds.height * fraction
        return inWindow.firstOrNull { index ->
            val element = elements[index]
            text(element) != null && !element.editable && element.bounds.centerY < bottom && !ScreenTree.insideScrollable(snapshot, index)
        }?.let { text(elements[it]) }
    }

    private fun text(element: ScreenElement): String? = element.text?.trim()?.takeIf { it.isNotEmpty() }

    private fun named(element: ScreenElement): String? =
        listOf(element.text, element.contentDescription, element.label).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
}
