package dev.kestrel.agent.tools

import dev.kestrel.agent.observe.ObservationTable
import dev.kestrel.agent.observe.TableEntry
import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.Direction
import dev.kestrel.agent.port.GlobalKind
import dev.kestrel.agent.port.Press
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.safety.ActionKind
import dev.kestrel.safety.Category
import dev.kestrel.safety.Point
import dev.kestrel.safety.Proposal
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.screen.model.ScrollAxis
import java.time.LocalDate

/**
 * Maps a checked call onto the safety check's [Proposal], the screen's [ScreenAction] and the [DataPort] (design D10
 * to D13 of sub-project 5). Every action on an element carries the [dev.kestrel.agent.port.Expect] of the observation's
 * table (design D32 item 27), so that `:app` does nothing when the element changed.
 */
object ActionMapper {
    /** The sentence of a refused sideways swipe (design D10). */
    const val SWIPE_REFUSAL = "a sideways swipe on a list row can archive, delete or call"

    /**
     * The proposal for the safety check. `share_media`'s [Proposal.shareCount] is left to the loop, which knows the kinds
     * of the task's media items. For `open_url` and `open_deep_link`, [receivingAppLabel] is the label of the app that
     * opens the link, which the link's question names.
     */
    fun proposal(
        call: AgentCall,
        table: ObservationTable,
        frontAppLabel: String?,
        receivingPackage: String? = null,
        receivingAppLabel: String? = null,
        linkFromOwner: Boolean = false,
        linkTargetPackage: String? = null,
    ): Proposal {
        fun of(kind: ActionKind, declared: Category? = null, target: TableEntry? = null, text: String? = null) =
            Proposal(call.tool, kind, declared, targetKey = target?.key, text = text, frontAppLabel = frontAppLabel)
        return when (call) {
            is AgentCall.Tap -> {
                val entry = table.entry(call.target)
                if (entry != null && entry.ocr) {
                    Proposal(call.tool, ActionKind.TAP_AT, call.category, point = Point(entry.bounds.centerX, entry.bounds.centerY), pointText = entry.label, frontAppLabel = frontAppLabel)
                } else {
                    of(ActionKind.TAP, call.category, entry)
                }
            }
            is AgentCall.LongPress -> of(ActionKind.LONG_PRESS, call.category, table.entry(call.target))
            is AgentCall.TypeText -> of(ActionKind.TYPE, call.category, table.entry(call.target), call.text)
            is AgentCall.Scroll -> of(ActionKind.SCROLL, call.category, call.target?.let(table::entry) ?: largestScrollable(table))
            is AgentCall.Swipe -> of(ActionKind.SWIPE, call.category, call.target?.let(table::entry))
            is AgentCall.SetSlider -> of(ActionKind.SET_SLIDER, call.category, table.entry(call.target)).copy(percent = call.percent)
            is AgentCall.Back -> of(ActionKind.BACK)
            is AgentCall.Home -> of(ActionKind.HOME)
            is AgentCall.Wait -> of(ActionKind.WAIT)
            is AgentCall.LookCloser -> of(ActionKind.LOOK_CLOSER, target = call.target?.let(table::entry))
            is AgentCall.OpenApp -> of(ActionKind.OPEN_APP)
            is AgentCall.OpenUrl -> of(ActionKind.OPEN_URL)
                .copy(link = call.url, linkFromOwner = linkFromOwner, linkTargetPackage = linkTargetPackage, receivingAppLabel = receivingAppLabel)
            is AgentCall.OpenDeepLink -> of(ActionKind.OPEN_DEEP_LINK)
                .copy(link = call.uri, linkFromOwner = linkFromOwner, linkTargetPackage = linkTargetPackage, receivingAppLabel = receivingAppLabel)
            is AgentCall.OpenSettings -> of(ActionKind.OPEN_SETTINGS)
            is AgentCall.FindContact, is AgentCall.FindMedia, is AgentCall.ListEvents, AgentCall.ListNotifications,
            is AgentCall.ListSms, is AgentCall.ListCalls, AgentCall.GetLocation -> of(ActionKind.READ_DATA)
            is AgentCall.SetAlarm, is AgentCall.SetTimer, is AgentCall.CreateEvent, is AgentCall.MediaControl, is AgentCall.Navigate ->
                of(ActionKind.SETTINGS_DATA)
            is AgentCall.ShareMedia -> of(ActionKind.SHARE_MEDIA).copy(receivingPackage = receivingPackage, receivingAppLabel = receivingAppLabel)
            is AgentCall.ComposeSms -> of(ActionKind.COMPOSE_SMS, text = call.text).copy(receivingPackage = receivingPackage, receivingAppLabel = receivingAppLabel)
            is AgentCall.AskUser, is AgentCall.Say, is AgentCall.Finish, is AgentCall.Fail -> of(ActionKind.DIALOGUE)
            is AgentCall.Remember, is AgentCall.Forget -> of(ActionKind.MEMORY)
        }
    }

    /**
     * The screen action of [call], or null for a call that is none (the data, dialogue and memory tools, `open_*`, `wait`
     * and `look_closer`, which the loop runs itself). [text] replaces `type_text`'s text: the revealed text after the
     * owner's yes. An OCR line is tapped at its centre. A `scroll` without a target scrolls the largest scrollable
     * element of the front window, else of the observation, or the screen when there is none.
     */
    fun screenAction(call: AgentCall, table: ObservationTable, text: String? = null, press: Press): ScreenAction? = when (call) {
        is AgentCall.Tap -> table.entry(call.target)?.let { entry ->
            if (entry.ocr || entry.key == null) ScreenAction.TapAt(entry.bounds.centerX, entry.bounds.centerY) else ScreenAction.Tap(entry.key, entry.expect, press)
        }
        is AgentCall.LongPress -> table.entry(call.target)?.let { entry -> entry.key?.let { ScreenAction.LongPress(it, entry.expect, press) } }
        is AgentCall.TypeText -> table.entry(call.target)?.let { entry -> entry.key?.let { ScreenAction.SetText(it, entry.expect, text ?: call.text) } }
        is AgentCall.Scroll -> {
            val entry = call.target?.let(table::entry) ?: largestScrollable(table)
            ScreenAction.Scroll(entry?.key, call.direction, entry?.expect)
        }
        is AgentCall.Swipe -> {
            val entry = call.target?.let(table::entry)
            ScreenAction.Swipe(entry?.key, call.direction, entry?.expect)
        }
        is AgentCall.SetSlider -> table.entry(call.target)?.let { entry -> entry.key?.let { ScreenAction.SetSlider(it, entry.expect, call.percent) } }
        is AgentCall.Back -> ScreenAction.Global(GlobalKind.BACK)
        is AgentCall.Home -> ScreenAction.Global(GlobalKind.HOME)
        else -> null
    }

    /**
     * [Press.ONCE] for every action of `TYPING_SCREEN_TEXT` and above and every action after a confirmation, else
     * [Press.VERIFIED] (design D32 item 29).
     */
    fun press(category: Category, confirmed: Boolean): Press =
        if (confirmed || category >= Category.TYPING_SCREEN_TEXT) Press.ONCE else Press.VERIFIED

    /**
     * Why a `swipe` is refused, or null (design D10): up and down are free; left and right only on an element that
     * scrolls sideways itself, or across a front window without a list that scrolls up and down.
     */
    fun swipeRefusal(call: AgentCall.Swipe, table: ObservationTable, snapshot: ScreenSnapshot): String? {
        if (call.direction == Direction.UP || call.direction == Direction.DOWN) return null
        val target = call.target?.let(table::entry)
        if (target != null) return if (target.scrollable && target.scrollAxis == ScrollAxis.HORIZONTAL) null else SWIPE_REFUSAL
        val window = ScreenTree.frontWindow(snapshot) ?: return null
        val list = snapshot.elements.any { it.windowId == window.id && ScreenTree.isVerticalList(it) }
        return if (list) SWIPE_REFUSAL else null
    }

    /**
     * Runs a data tool, or returns null for a call that is none. `share_media` needs [sharePackage], the package that the
     * loop resolved from the app's name.
     */
    suspend fun runData(call: AgentCall, port: DataPort, today: LocalDate, sharePackage: String? = null): DataResult? = when (call) {
        is AgentCall.FindContact -> port.findContact(call.name)
        is AgentCall.FindMedia -> {
            val from = Dates.day(call.day, today)
            val until = call.until?.let { Dates.day(it, today) } ?: from
            if (from == null || until == null) DataResult.Failed(BAD_DATE) else port.findMedia(from, until, call.kinds)
        }
        is AgentCall.ShareMedia -> sharePackage?.let { port.shareMedia(it, call.items) } ?: DataResult.Failed("Kestrel can't share with that app.")
        is AgentCall.ListEvents -> {
            val from = Dates.day(call.from, today)
            val to = Dates.day(call.to, today)
            if (from == null || to == null) DataResult.Failed(BAD_DATE) else port.listEvents(from, to)
        }
        is AgentCall.CreateEvent -> {
            val start = Dates.dateTime(call.start)
            if (start == null) DataResult.Failed(BAD_DATE) else port.createEvent(call.title, start, call.end?.let(Dates::dateTime), call.location)
        }
        is AgentCall.SetAlarm -> port.setAlarm(call.hour, call.minute, call.label, call.days)
        is AgentCall.SetTimer -> port.setTimer(call.seconds, call.label)
        AgentCall.ListNotifications -> port.listNotifications()
        is AgentCall.ListSms -> port.listSms(call.from, call.limit)
        is AgentCall.ComposeSms -> port.composeSms(call.number, call.text)
        is AgentCall.ListCalls -> port.listCalls(call.limit)
        AgentCall.GetLocation -> port.getLocation()
        is AgentCall.MediaControl -> port.mediaControl(call.action)
        is AgentCall.Navigate -> port.navigate(call.app, call.to, call.mode)
        else -> null
    }

    private const val BAD_DATE = "The date is not valid."

    /**
     * The largest scrollable entry of the front window (design D10), so that a dialog's or a picker's list wins over the
     * bigger list behind it; the largest of the whole observation when the front window has none.
     */
    private fun largestScrollable(table: ObservationTable): TableEntry? {
        val lists = table.entries.filter { it.scrollable && !it.ocr }
        val front = lists.filter { it.windowId == table.frontWindowId }
        return front.ifEmpty { lists }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
    }
}
