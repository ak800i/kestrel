package dev.kestrel.agent.observe

import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.LabelWords
import dev.kestrel.safety.Redactor
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.Region
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSignature
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTitle
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.screen.model.ScrollAxis
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import kotlin.math.abs

/**
 * Turns a snapshot into the observation that the model reads (design D8 and D9 of sub-project 5; spec 11.3):
 *
 * ```
 * Observation 4 · App: Viber (com.viber.voip) · Screen: "Ana Petrović" · Keyboard: shown · Focus: [14]
 * Last: tapped "Ana Petrović" (row) → another screen
 * [3] ImageButton "Navigate up" @top-left
 * [14] EditText hint "Type a message…" value "" focused @bottom
 * [15]* Button "Send" @bottom-right
 * Notifications: hidden (2 showing; use list_notifications)
 * ```
 *
 * Every string from the screen is redacted with its neighbours (design D17), then cut, escaped and quoted. The keyboard,
 * agent-free apps, Play services and SystemUI (except a Quick Settings tile) never appear.
 */
class ObservationBuilder(
    private val redactor: Redactor,
    private val agentFree: AgentFreeApps,
    private val parts: SystemUiParts = SystemUiParts.DEFAULT,
) {
    private class Line(
        val element: ScreenElement?,
        val windowId: Int,
        val bounds: IntRect,
        val actionable: Boolean,
        val raw: String?,
        val region: Region,
        val star: Boolean,
        val order: Int,
    ) {
        var body = ""
        var shortBody: String? = null
        var shown = NO_LABEL
        var shortShown: String? = null
        var short = false
        var isProtected = false
        var decoration = false
        var list: Int? = null
        var leftOutAfter = 0
        var ocrText: String? = null
        var redacted = false

        fun text(): String = if (short && shortBody != null) shortBody!! else body

        fun shownLabel(): String = if (short && shortShown != null) shortShown!! else shown
    }

    fun build(input: ObservationInput): Observation {
        val snapshot = input.snapshot
        val elements = snapshot.elements
        val front = ScreenTree.frontWindow(snapshot)?.takeIf { shown(it) }
        val windows = snapshot.windows.filter { shown(it) }
        val shownIds = windows.map { it.id }.toSet()
        val largest = ScreenTree.appWindows(snapshot).maxOfOrNull { area(it.bounds) } ?: 0L
        val split = snapshot.windows.any { it.kind == WindowKind.SPLIT_SCREEN_DIVIDER }
        val dialogs = windows.filter { it.kind != WindowKind.SPLIT_SCREEN_DIVIDER && !isSystemUi(it) && !split && area(it.bounds) < largest * 0.9 }
            .map { it.id }.toSet()

        val visible = BooleanArray(elements.size)
        var notifications = 0
        for ((index, element) in elements.withIndex()) {
            val window = snapshot.windows.firstOrNull { it.id == element.windowId }
            if (window == null || window.id !in shownIds || hidden(element.packageName)) continue
            if (isSystemUi(window)) {
                val chain = listOf(index) + ScreenTree.ancestors(snapshot, index)
                if (parts.isNotification(element) && ScreenTree.ancestors(snapshot, index).none { parts.isNotification(elements[it]) }) notifications++
                if (chain.any { parts.isTile(elements[it]) } && chain.none { parts.isNotification(elements[it]) }) visible[index] = true
                continue
            }
            visible[index] = true
        }

        // A clickable element takes the text of the descendants that are not actionable; they get no line. A clickable
        // container of screen size (a fragment's root that stops touches) is no row, so its texts keep their lines.
        val owner = IntArray(elements.size) { -1 }
        for (index in elements.indices) {
            if (!visible[index] || actionable(elements[index]) || ownLabel(elements[index]) == null) continue
            val nearest = ScreenTree.ancestors(snapshot, index).firstOrNull { visible[it] && actionable(elements[it]) } ?: continue
            val parent = elements[nearest]
            if ((parent.clickable || parent.longClickable) && !parent.editable && !parent.scrollable && !screenSized(snapshot, parent)) {
                owner[index] = nearest
            }
        }
        val pieces = HashMap<Int, MutableList<String>>()
        for (index in readingOrder(elements.indices.filter { owner[it] >= 0 }, elements)) {
            pieces.getOrPut(owner[index]) { mutableListOf() } += ownLabel(elements[index])!!
        }

        val signature = ScreenSignature.of(snapshot)
        val previous = input.previous
        // Within one step a new window is another screen, so the stars compare the window too (review 1-2 N1).
        val stars = previous != null && previous.packageName == front?.packageName && previous.signature.windowId == signature.windowId &&
            previous.signature.sameScreen(signature)
        val lines = mutableListOf<Line>()
        val rowText = HashMap<Int, String>()
        val lineOf = HashMap<Int, Line>()
        for (window in windows) {
            val candidates = elements.indices.filter {
                visible[it] && elements[it].windowId == window.id && owner[it] < 0 && (actionable(elements[it]) || ownLabel(elements[it]) != null)
            }
            // Regions above the keyboard, so that the keyboard never moves the app's bottom bar to another cell (review 3 I3).
            val area = ScreenTree.visibleArea(snapshot, window)
            val ordered = readingOrder(candidates, elements)
            val raws = ordered.map { index ->
                val element = elements[index]
                val all = (listOfNotNull(ownLabel(element).takeUnless { element.editable }) + pieces[index].orEmpty()).distinct().take(3)
                if (all.size > 1 || pieces[index] != null) rowText[index] = all.joinToString(" · ")
                when {
                    element.editable -> listOfNotNull(fieldName(element)?.first, fieldValue(element)).joinToString(" ")
                    else -> all.joinToString(" · ").ifEmpty { null }
                }
            }
            for ((position, index) in ordered.withIndex()) {
                val element = elements[index]
                val neighbours = listOfNotNull(raws.getOrNull(position - 1), raws.getOrNull(position + 1)) +
                    listOfNotNull(ScreenTree.ancestors(snapshot, index).firstOrNull { it in rowText }?.let { rowText[it] })
                val line = Line(
                    element = element,
                    windowId = window.id,
                    bounds = element.bounds,
                    actionable = actionable(element),
                    raw = raws[position],
                    region = Region.of(element.bounds, area),
                    star = stars && element.key !in previous.keys,
                    order = lines.size,
                )
                describe(line, element, pieces[index].orEmpty(), neighbours)
                line.isProtected = element.focused || element.editable || window.id in dialogs || element.key == input.lastTargetKey ||
                    LabelWords.categoryOf(raws[position]) != null
                line.decoration = !line.actionable && (ScreenText.shortClass(element.className) == "Image" || (raws[position]?.trim()?.length ?: 0) <= 1)
                line.list = ScreenTree.ancestors(snapshot, index).firstOrNull { elements[it].scrollable }
                lines += line
                lineOf[index] = line
            }
        }
        val ocrArea = front?.let { ScreenTree.visibleArea(snapshot, it) } ?: snapshot.windows.firstOrNull()?.bounds ?: IntRect(0, 0, 1, 1)
        for ((position, ocr) in input.ocr.withIndex()) {
            val neighbours = listOfNotNull(input.ocr.getOrNull(position - 1)?.text, input.ocr.getOrNull(position + 1)?.text)
            lines += Line(null, front?.id ?: -1, ocr.bounds, true, ocr.text, Region.of(ocr.bounds, ocrArea), false, lines.size).apply {
                val shown = ScreenText.quote(redact(this, ocr.text, neighbours), ScreenText.LABEL_LIMIT)
                body = "OCR $shown"
                this.shown = shown
                ocrText = ocr.text
            }
        }

        val title = front?.let { ScreenTitle.of(snapshot, it, input.appLabel) }?.let { redactor.redact(it) }
        // The launcher label comes from the app, so it is redacted like screen text (review 3 nit 3).
        val shownApp = input.appLabel?.let { redactor.redact(it) }
        val header = { numbers: Map<Line, Int> ->
            val focus = lines.firstOrNull { it.element?.focused == true && it.element.editable } ?: lines.firstOrNull { it.element?.focused == true }
            listOfNotNull(
                "Observation ${input.step}",
                "App: " + appText(shownApp, front?.packageName),
                title?.let { "Screen: " + ScreenText.quote(it, ScreenText.LABEL_LIMIT) },
                "Keyboard: " + if (snapshot.windows.any { it.kind == WindowKind.INPUT_METHOD }) "shown" else "hidden",
                focus?.let { numbers[it] }?.let { "Focus: [$it]" },
                "Dialog".takeIf { snapshot.windows.firstOrNull { it.active }?.id in dialogs },
            ).joinToString(" · ")
        }
        var dropped = 0
        fun render(): Pair<String, Map<Line, Int>> {
            val numbers = lines.withIndex().associate { (position, line) -> line to position + 1 }
            val out = mutableListOf(header(numbers))
            input.last?.let { out += "Last: $it" }
            for (line in lines) {
                out += "[${numbers[line]}]" + (if (line.star) "*" else "") + " " + line.text() + " @" + line.region.label
                if (line.leftOutAfter > 0) out += "(${line.leftOutAfter} lines left out)"
            }
            if (notifications > 0) out += "Notifications: hidden ($notifications showing; use list_notifications)"
            if (dropped > 0) out += "($dropped more elements; scroll or look closer to see them)"
            return out.joinToString("\n") to numbers
        }

        // Every line whose text the redactor changed, also one that the cap leaves out, so that a screenshot's masks
        // cover it (design D20; review 3-2 N1).
        val redactedBounds = lines.filter { it.redacted }.map { it.bounds }
        var rendered = render()
        fun fits() = ScreenText.tokens(rendered.first) <= TOKEN_CAP
        if (!fits()) {
            lines.removeAll { it.decoration && !it.isProtected }
            rendered = render()
        }
        if (!fits()) {
            lines.forEach { it.short = true }
            rendered = render()
        }
        if (!fits()) {
            lines.filter { !it.actionable && !it.isProtected && it.list != null }.groupBy { it.list }.values.filter { it.size > 10 }.forEach { group ->
                val middle = group.subList(5, group.size - 5)
                group[4].leftOutAfter = middle.size
                lines.removeAll(middle.toSet())
            }
            rendered = render()
        }
        if (!fits()) {
            val anchors = lines.filter { it.element?.focused == true || (input.lastTargetKey != null && it.element?.key == input.lastTargetKey) }.map { it.bounds }
            val order = lines.filter { !it.isProtected && it.leftOutAfter == 0 }.sortedByDescending { line ->
                if (anchors.isEmpty()) line.order.toLong() else anchors.minOf { distance(it, line.bounds) }
            }
            // A running estimate, so that a screen of a thousand elements is not rendered once per dropped line (review 3
            // nit 8); the real text is rendered again whenever the estimate fits.
            var estimate = ScreenText.tokens(rendered.first)
            for (line in order) {
                if (estimate <= TOKEN_CAP) {
                    rendered = render()
                    if (fits()) break
                    estimate = ScreenText.tokens(rendered.first)
                }
                // An upper bound of the line's real cost (number, star, region and line break), so that the estimate
                // never stays above the real text and the cut stops near the cap (review 3-2 N2).
                estimate -= ScreenText.tokens("[${lines.size}]* " + line.text() + " @" + line.region.label) + 1
                lines.remove(line)
                dropped++
            }
            rendered = render()
        }

        val (text, numbers) = rendered
        val entries = lines.map { line ->
            val element = line.element
            TableEntry(
                number = numbers.getValue(line),
                key = element?.key,
                label = element?.label ?: line.ocrText,
                shownLabel = line.shownLabel(),
                className = element?.className,
                packageName = element?.packageName ?: front?.packageName,
                windowId = line.windowId,
                bounds = line.bounds,
                editable = element?.editable ?: false,
                password = element?.password ?: false,
                checkable = element?.checkable ?: false,
                checked = element?.checked ?: false,
                scrollable = element?.scrollable ?: false,
                scrollAxis = element?.scrollAxis,
                ocr = element == null,
                redacted = line.redacted,
            )
        }
        return Observation(
            step = input.step,
            text = text,
            table = ObservationTable(entries, redactedBounds, front?.id),
            packageName = front?.packageName,
            appLabel = input.appLabel,
            title = title,
            signature = signature,
            keys = lineOf.values.mapNotNull { it.element?.key }.toSet(),
            notifications = notifications,
            shownAppLabel = shownApp,
        )
    }

    /** Fills the line's text after its number and before its region, and the label that the table keeps. */
    private fun describe(line: Line, element: ScreenElement, pieces: List<String>, neighbours: List<String>) {
        val kind = ScreenText.shortClass(element.className)
        val words = mutableListOf(kind)
        if (element.editable) {
            val name = fieldName(element)
            if (name != null) {
                val quoted = ScreenText.quote(redact(line, name.first, neighbours), ScreenText.LABEL_LIMIT)
                words += if (name.second) "hint $quoted" else quoted
                line.shown = quoted
            }
            if (element.password) {
                words += "password"
            } else {
                val value = fieldValue(element).orEmpty()
                words += "value " + ScreenText.quote(redact(line, value, neighbours + listOfNotNull(name?.first)), ScreenText.VALUE_LIMIT)
            }
        } else {
            val all = (listOfNotNull(ownLabel(element)) + pieces).distinct().take(3)
            // Read once: a smart cast of another module's property does not compile.
            val suggested = element.suggestedName
            when {
                all.isNotEmpty() -> {
                    val limit = when {
                        pieces.isNotEmpty() -> ScreenText.ROW_LIMIT
                        actionable(element) -> ScreenText.LABEL_LIMIT
                        else -> ScreenText.VALUE_LIMIT
                    }
                    // Each text of a row is redacted with the row's other texts as neighbours, as it would be on a line of
                    // its own, so that a keyword far from a code in another text still counts (design D8 and D17). The
                    // texts after the third are redacted too: a screenshot shows them, so they mark the line for its mask
                    // (design D20).
                    val texts = (listOfNotNull(ownLabel(element)) + pieces).distinct()
                    val redacted = texts.mapIndexed { position, text ->
                        redact(line, text, neighbours + texts.filterIndexed { other, _ -> other != position })
                    }
                    line.shown = ScreenText.quote(redacted.take(3).joinToString(" · "), limit)
                    words += line.shown
                    if (all.size > 1) line.shortShown = ScreenText.quote(redacted.first(), ScreenText.LABEL_LIMIT)
                }
                suggested != null -> {
                    line.shown = "id " + ScreenText.quote(redact(line, suggested, neighbours), ScreenText.LABEL_LIMIT)
                    words += line.shown
                }
            }
        }
        val states = states(line, element, neighbours)
        line.body = (words + states).joinToString(" ")
        line.shortShown?.let { line.shortBody = (listOf(kind, it) + states).joinToString(" ") }
    }

    private fun states(line: Line, element: ScreenElement, neighbours: List<String>): List<String> = buildList {
        if (element.checkable) add(if (element.checked) "checked" else "unchecked")
        if (element.selected) add("selected")
        if (!element.enabled) add("disabled")
        if (element.focused) add("focused")
        if (ElementAction.COLLAPSE in element.actions) add("expanded") else if (ElementAction.EXPAND in element.actions) add("collapsed")
        element.stateDescription?.trim()?.takeIf { it.isNotEmpty() }?.let { add(ScreenText.quote(redact(line, it, neighbours), ScreenText.LABEL_LIMIT)) }
        if (element.scrollable) add(scroll(element))
    }

    /** [text] redacted with its [neighbours]; the [line] remembers when the redactor changed anything, for the masks of D20. */
    private fun redact(line: Line, text: String, neighbours: List<String>): String =
        redactor.redact(text, neighbours).also { if (it != text) line.redacted = true }
    /** The directions in which [element] scrolls, from its actions and its axis; "scroll" alone when they are unknown. */
    private fun scroll(element: ScreenElement): String {
        val actions = element.actions
        val directions = linkedSetOf<String>()
        val axis = element.scrollAxis
        for (direction in listOf("up", "down", "left", "right")) {
            val explicit = when (direction) {
                "up" -> ElementAction.SCROLL_UP in actions || (axis == ScrollAxis.VERTICAL && ElementAction.SCROLL_BACKWARD in actions)
                "down" -> ElementAction.SCROLL_DOWN in actions || (axis == ScrollAxis.VERTICAL && ElementAction.SCROLL_FORWARD in actions)
                "left" -> ElementAction.SCROLL_LEFT in actions || (axis == ScrollAxis.HORIZONTAL && ElementAction.SCROLL_BACKWARD in actions)
                else -> ElementAction.SCROLL_RIGHT in actions || (axis == ScrollAxis.HORIZONTAL && ElementAction.SCROLL_FORWARD in actions)
            }
            if (explicit) directions += direction
        }
        if (directions.isEmpty()) return "scroll"
        val more = directions.map { MORE.getValue(it) }
        return "scroll ${directions.joinToString("/")} (more ${more.joinToString(" and ")})"
    }

    private fun shown(window: WindowSummary): Boolean =
        window.kind != WindowKind.INPUT_METHOD && window.kind != WindowKind.SPLIT_SCREEN_DIVIDER && !hidden(window.packageName)

    private fun hidden(packageName: String?): Boolean = agentFree.isAgentFree(packageName) || packageName == AgentFreeApps.PLAY_SERVICES

    private fun isSystemUi(window: WindowSummary): Boolean = window.packageName == SystemUiParts.SYSTEM_UI

    /** Whether [element] covers at least three quarters of its window's visible area, as a screen's root or a fragment's does. */
    private fun screenSized(snapshot: ScreenSnapshot, element: ScreenElement): Boolean {
        val window = snapshot.windows.firstOrNull { it.id == element.windowId } ?: return false
        val visible = ScreenTree.visibleArea(snapshot, window)
        val covered = element.bounds.intersect(visible)?.let { area(it) } ?: return false
        return covered * 4 >= area(visible) * 3
    }

    /** The header's app: its label and package, both escaped, because an app chooses both itself (review 3 I1). */
    private fun appText(label: String?, packageName: String?): String {
        val name = label?.trim()?.takeIf { it.isNotEmpty() }?.let { ScreenText.escape(ScreenText.cut(it, ScreenText.LABEL_LIMIT)) }
        val pkg = packageName?.let { ScreenText.escape(ScreenText.cut(it, ScreenText.LABEL_LIMIT)) }
        return when {
            name != null && pkg != null -> "$name ($pkg)"
            else -> name ?: pkg ?: "none"
        }
    }

    private companion object {
        const val TOKEN_CAP = 2500
        const val NO_LABEL = "(no label)"
        val MORE = mapOf("up" to "above", "down" to "below", "left" to "left", "right" to "right")

        fun actionable(element: ScreenElement): Boolean =
            element.clickable || element.longClickable || element.editable || element.checkable || element.scrollable || element.slider

        /** The label's sources in spec 9.2's order, with the tooltip last. */
        fun ownLabel(element: ScreenElement): String? =
            listOf(element.text, element.contentDescription, element.supplementalDescription, element.hint, element.tooltip)
                .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }

        /** A field's name and whether it is its hint: its own description, supplemental description, else its hint. */
        fun fieldName(element: ScreenElement): Pair<String, Boolean>? {
            listOf(element.contentDescription, element.supplementalDescription).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
                ?.let { return it to false }
            element.hint?.trim()?.takeIf { it.isNotEmpty() }?.let { return it to true }
            return element.tooltip?.trim()?.takeIf { it.isNotEmpty() }?.let { it to false }
        }

        /** A field's value apart from its hint: a field that shows only its hint is empty (spec 9.2). */
        fun fieldValue(element: ScreenElement): String? {
            if (element.password) return null
            val text = element.text ?: return ""
            val hint = element.hint?.trim()
            return if (hint != null && text.trim() == hint) "" else text
        }

        fun readingOrder(indices: List<Int>, elements: List<ScreenElement>): List<Int> =
            indices.sortedWith(compareBy({ elements[it].bounds.top }, { elements[it].bounds.left }))

        fun area(bounds: IntRect): Long = bounds.width.toLong() * bounds.height

        fun distance(a: IntRect, b: IntRect): Long = abs(a.centerX - b.centerX).toLong() + abs(a.centerY - b.centerY)
    }
}
