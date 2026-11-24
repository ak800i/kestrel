package dev.kestrel.agent.tools

import dev.kestrel.agent.port.Direction
import dev.kestrel.agent.port.MediaAction
import dev.kestrel.agent.port.MediaKinds
import dev.kestrel.agent.port.NavigationApp
import dev.kestrel.agent.port.TravelMode
import dev.kestrel.safety.Category
import java.time.DayOfWeek

/**
 * A tool call whose arguments `ToolArguments` has checked (design D10 of sub-project 5). Its text form prints the tool
 * and lengths only, because the arguments can carry screen text and the owner's words (design D25).
 */
sealed interface AgentCall {
    val tool: String

    /** The call's `reason`, for the tools that have one. */
    val reason: String? get() = null

    data class Tap(val target: Int, override val reason: String, val category: Category) : AgentCall {
        override val tool: String get() = Tools.TAP

        override fun toString(): String = "Tap(target=$target, category=$category, reason=${reason.length} chars)"
    }

    data class LongPress(val target: Int, override val reason: String, val category: Category) : AgentCall {
        override val tool: String get() = Tools.LONG_PRESS

        override fun toString(): String = "LongPress(target=$target, category=$category, reason=${reason.length} chars)"
    }

    data class TypeText(val target: Int, val text: String, override val reason: String, val category: Category) : AgentCall {
        override val tool: String get() = Tools.TYPE_TEXT

        override fun toString(): String = "TypeText(target=$target, text=${text.length} chars, category=$category, reason=${reason.length} chars)"
    }

    data class Scroll(val direction: Direction, val target: Int?, override val reason: String, val category: Category) : AgentCall {
        override val tool: String get() = Tools.SCROLL

        override fun toString(): String = "Scroll(direction=$direction, target=$target, category=$category, reason=${reason.length} chars)"
    }

    data class Swipe(val direction: Direction, val target: Int?, override val reason: String, val category: Category) : AgentCall {
        override val tool: String get() = Tools.SWIPE

        override fun toString(): String = "Swipe(direction=$direction, target=$target, category=$category, reason=${reason.length} chars)"
    }

    data class SetSlider(val target: Int, val percent: Int, override val reason: String, val category: Category) : AgentCall {
        override val tool: String get() = Tools.SET_SLIDER

        override fun toString(): String = "SetSlider(target=$target, percent=$percent, category=$category, reason=${reason.length} chars)"
    }

    data class Back(override val reason: String) : AgentCall {
        override val tool: String get() = Tools.BACK

        override fun toString(): String = "Back(reason=${reason.length} chars)"
    }

    data class Home(override val reason: String) : AgentCall {
        override val tool: String get() = Tools.HOME

        override fun toString(): String = "Home(reason=${reason.length} chars)"
    }

    data class OpenApp(val name: String, override val reason: String) : AgentCall {
        override val tool: String get() = Tools.OPEN_APP

        override fun toString(): String = "OpenApp(name=${name.length} chars, reason=${reason.length} chars)"
    }

    data class OpenUrl(val url: String, val browser: Browser, override val reason: String) : AgentCall {
        override val tool: String get() = Tools.OPEN_URL

        override fun toString(): String = "OpenUrl(url=${url.length} chars, browser=$browser, reason=${reason.length} chars)"
    }

    data class OpenDeepLink(val uri: String, override val reason: String) : AgentCall {
        override val tool: String get() = Tools.OPEN_DEEP_LINK

        override fun toString(): String = "OpenDeepLink(uri=${uri.length} chars, reason=${reason.length} chars)"
    }

    data class OpenSettings(val page: SettingsPage, override val reason: String) : AgentCall {
        override val tool: String get() = Tools.OPEN_SETTINGS

        override fun toString(): String = "OpenSettings(page=$page, reason=${reason.length} chars)"
    }

    data class Wait(val seconds: Int, override val reason: String) : AgentCall {
        override val tool: String get() = Tools.WAIT

        override fun toString(): String = "Wait(seconds=$seconds, reason=${reason.length} chars)"
    }

    data class LookCloser(val target: Int?, override val reason: String) : AgentCall {
        override val tool: String get() = Tools.LOOK_CLOSER

        override fun toString(): String = "LookCloser(target=$target, reason=${reason.length} chars)"
    }

    data class FindContact(val name: String) : AgentCall {
        override val tool: String get() = Tools.FIND_CONTACT

        override fun toString(): String = "FindContact(name=${name.length} chars)"
    }

    data class FindMedia(val day: String, val until: String?, val kinds: MediaKinds) : AgentCall {
        override val tool: String get() = Tools.FIND_MEDIA

        override fun toString(): String = "FindMedia(day=${day.length} chars, until=${until?.length ?: 0} chars, kinds=$kinds)"
    }

    data class AddContact(val name: String, val number: String) : AgentCall {
        override val tool: String get() = Tools.ADD_CONTACT

        override fun toString(): String = "AddContact(name=${name.length} chars, number=${number.length} chars)"
    }

    /** [contact] is the contact's number in the task's newest `find_contact` result (design D19 of sub-project 6). */
    data class DeleteContact(val contact: Int) : AgentCall {
        override val tool: String get() = Tools.DELETE_CONTACT
    }

    data class ShareMedia(val app: String, val items: List<String>) : AgentCall {
        override val tool: String get() = Tools.SHARE_MEDIA

        override fun toString(): String = "ShareMedia(app=${app.length} chars, items=${items.size})"
    }

    data class ListEvents(val from: String, val to: String) : AgentCall {
        override val tool: String get() = Tools.LIST_EVENTS

        override fun toString(): String = "ListEvents(from=${from.length} chars, to=${to.length} chars)"
    }

    data class CreateEvent(val title: String, val start: String, val end: String?, val location: String?) : AgentCall {
        override val tool: String get() = Tools.CREATE_EVENT

        override fun toString(): String =
            "CreateEvent(title=${title.length} chars, start=${start.length} chars, end=${end?.length ?: 0} chars, location=${location?.length ?: 0} chars)"
    }

    data class SetAlarm(val hour: Int, val minute: Int, val label: String?, val days: List<DayOfWeek>) : AgentCall {
        override val tool: String get() = Tools.SET_ALARM

        override fun toString(): String = "SetAlarm(label=${label?.length ?: 0} chars, days=${days.size})"
    }

    data class SetTimer(val seconds: Int, val label: String?) : AgentCall {
        override val tool: String get() = Tools.SET_TIMER

        override fun toString(): String = "SetTimer(seconds=$seconds, label=${label?.length ?: 0} chars)"
    }

    data object ListNotifications : AgentCall {
        override val tool: String get() = Tools.LIST_NOTIFICATIONS
    }

    data class ListSms(val from: String?, val limit: Int) : AgentCall {
        override val tool: String get() = Tools.LIST_SMS

        override fun toString(): String = "ListSms(from=${from?.length ?: 0} chars, limit=$limit)"
    }

    data class ComposeSms(val number: String, val text: String) : AgentCall {
        override val tool: String get() = Tools.COMPOSE_SMS

        override fun toString(): String = "ComposeSms(number=${number.length} chars, text=${text.length} chars)"
    }

    data class ListCalls(val limit: Int) : AgentCall {
        override val tool: String get() = Tools.LIST_CALLS
    }

    data object GetLocation : AgentCall {
        override val tool: String get() = Tools.GET_LOCATION
    }

    data class MediaControl(val action: MediaAction) : AgentCall {
        override val tool: String get() = Tools.MEDIA_CONTROL
    }

    data class Navigate(val app: NavigationApp, val to: String, val mode: TravelMode?) : AgentCall {
        override val tool: String get() = Tools.NAVIGATE

        override fun toString(): String = "Navigate(app=$app, to=${to.length} chars, mode=$mode)"
    }

    data class AskUser(val question: String) : AgentCall {
        override val tool: String get() = Tools.ASK_USER

        override fun toString(): String = "AskUser(question=${question.length} chars)"
    }

    data class Say(val text: String) : AgentCall {
        override val tool: String get() = Tools.SAY

        override fun toString(): String = "Say(text=${text.length} chars)"
    }

    data class Finish(val summary: String, val evidence: List<Evidence>, val signature: String?, val notes: List<NoteDraft>) : AgentCall {
        override val tool: String get() = Tools.FINISH

        override fun toString(): String =
            "Finish(summary=${summary.length} chars, evidence=${evidence.size}, signature=${signature?.length ?: 0} chars, notes=${notes.size})"
    }

    data class Fail(override val reason: String) : AgentCall {
        override val tool: String get() = Tools.FAIL

        override fun toString(): String = "Fail(reason=${reason.length} chars)"
    }

    data class Remember(val key: String, val value: String) : AgentCall {
        override val tool: String get() = Tools.REMEMBER

        override fun toString(): String = "Remember(key=${key.length} chars, value=${value.length} chars)"
    }

    data class Forget(val key: String) : AgentCall {
        override val tool: String get() = Tools.FORGET

        override fun toString(): String = "Forget(key=${key.length} chars)"
    }
}

/**
 * One item of `finish`'s evidence: an element of the newest observation, or a data tool's step (design D21); [percent] is
 * the value that a cited slider should show (design D19 of sub-project 6).
 */
data class Evidence(val target: Int?, val step: Int?, val checked: Boolean?, val percent: Int? = null)

/** A route note that `finish` proposes for an app (design D22). */
data class NoteDraft(val app: String, val note: String) {
    override fun toString(): String = "NoteDraft(app=${app.length} chars, note=${note.length} chars)"
}

/** A tool call after its check: [Invalid.reason] is one of Kestrel's fixed sentences, never the model's text. */
sealed interface ParsedCall {
    data class Valid(val call: AgentCall) : ParsedCall

    data class Invalid(val reason: String) : ParsedCall
}
