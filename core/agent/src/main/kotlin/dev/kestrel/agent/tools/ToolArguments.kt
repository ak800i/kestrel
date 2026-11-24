package dev.kestrel.agent.tools

import dev.kestrel.agent.observe.ObservationTable
import dev.kestrel.agent.port.Direction
import dev.kestrel.agent.port.MediaAction
import dev.kestrel.agent.port.MediaKinds
import dev.kestrel.agent.port.NavigationApp
import dev.kestrel.agent.port.TravelMode
import dev.kestrel.safety.Category
import dev.kestrel.safety.Vault
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Checks a tool call's arguments against its schema and the limits of its description (design D4 and D10 of
 * sub-project 5): an unknown tool, a missing or mistyped argument, a value outside its list, a broken limit, a target that
 * the newest observation does not have, a placeholder that the task's vault does not hold, and a placeholder of the vault
 * anywhere but `type_text`'s text (typed after the owner's yes) and the texts that Kestrel speaks as its kind (`say`'s
 * text, `finish`'s summary and `fail`'s reason; design D17) give [ParsedCall.Invalid] with one of Kestrel's fixed
 * sentences, which names the argument but never repeats the model's text. A null argument and a blank optional text
 * count as left out, and an unknown argument is ignored.
 */
object ToolArguments {
    private class Bad(val reason: String) : Exception(null, null, false, false)

    private class Reader(val arguments: JsonObject, val table: ObservationTable) {
        private fun value(name: String): JsonElement? = arguments[name]?.takeUnless { it is JsonNull }

        private fun missing(name: String): Nothing = throw Bad("The argument $name is missing.")

        fun string(name: String, limit: Int? = null): String = optionalString(name, limit, blank = false) ?: missing(name)

        /** A blank optional text counts as left out, because models often send `""` for an optional field (review 3 M5). */
        fun optionalString(name: String, limit: Int? = null, blank: Boolean = true): String? {
            val element = value(name) ?: return null
            if (element !is JsonPrimitive || !element.isString) throw Bad("The argument $name must be a string.")
            val text = element.content
            if (text.isBlank()) {
                if (blank) return null
                throw Bad("The argument $name is empty.")
            }
            if (limit != null && text.length > limit) throw Bad("The argument $name is longer than $limit characters.")
            return text
        }

        fun int(name: String, range: IntRange? = null): Int = optionalInt(name, range) ?: missing(name)

        fun optionalInt(name: String, range: IntRange? = null): Int? {
            val element = value(name) ?: return null
            val number = (element as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            if (number == null || number % 1.0 != 0.0 || number !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                throw Bad("The argument $name must be an integer.")
            }
            val value = number.toInt()
            if (range != null && value !in range) throw Bad("The argument $name must be from ${range.first} to ${range.last}.")
            return value
        }

        fun optionalBoolean(name: String): Boolean? {
            val element = value(name) ?: return null
            return (element as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw Bad("The argument $name must be true or false.")
        }

        fun <T> choice(name: String, values: Map<String, T>): T = optionalChoice(name, values) ?: missing(name)

        fun <T> optionalChoice(name: String, values: Map<String, T>): T? {
            val text = optionalString(name) ?: return null
            return values[text] ?: throw Bad("The argument $name must be one of: ${values.keys.joinToString(", ")}.")
        }

        fun array(name: String): List<JsonElement>? {
            val element = value(name) ?: return null
            return element as? JsonArray ?: throw Bad("The argument $name must be an array.")
        }

        fun target(name: String = "target", typed: Boolean = false, pressed: Boolean = false, slider: Boolean = false): Int =
            optionalTarget(name, typed, pressed, slider) ?: missing(name)

        /**
         * A number of the newest observation; [typed] and [pressed] need an element (an OCR line can only be tapped), and
         * [slider] a slider (design D19 of sub-project 6).
         */
        fun optionalTarget(name: String = "target", typed: Boolean = false, pressed: Boolean = false, slider: Boolean = false): Int? {
            val number = optionalInt(name) ?: return null
            val entry = table.entry(number) ?: throw Bad("The newest observation has no [$number].")
            if (entry.ocr && (typed || pressed)) throw Bad("[$number] is an OCR line, which Kestrel can only tap.")
            if (typed && !entry.editable) throw Bad("[$number] is not an editable field.")
            if (slider && !entry.slider) throw Bad("[$number] is not a slider.")
            return number
        }
    }

    private val CATEGORIES = Category.entries.associateBy { it.wire }
    private val DIRECTIONS = mapOf("up" to Direction.UP, "down" to Direction.DOWN, "left" to Direction.LEFT, "right" to Direction.RIGHT)
    private val BROWSERS = Browser.entries.associateBy { it.wire }
    private val PAGES = SettingsPage.entries.associateBy { it.wire }
    private val KINDS = mapOf("photos" to MediaKinds.PHOTOS, "videos" to MediaKinds.VIDEOS, "both" to MediaKinds.BOTH)
    private val MEDIA = mapOf("play" to MediaAction.PLAY, "pause" to MediaAction.PAUSE, "next" to MediaAction.NEXT, "previous" to MediaAction.PREVIOUS)
    private val NAVIGATION = mapOf("waze" to NavigationApp.WAZE, "google_maps" to NavigationApp.GOOGLE_MAPS)
    private val MODES = mapOf("drive" to TravelMode.DRIVE, "bicycle" to TravelMode.BICYCLE, "walk" to TravelMode.WALK, "two_wheeler" to TravelMode.TWO_WHEELER)
    private val DAYS = Tools.DAYS.zip(DayOfWeek.values()).toMap()
    private val ITEM = Regex("m[0-9]+")
    private val ANY_DAY = LocalDate.of(2000, 1, 1)

    fun parse(name: String, arguments: JsonObject, table: ObservationTable, vault: Vault): ParsedCall {
        if (Tools.spec(name) == null) return ParsedCall.Invalid("Kestrel has no such tool.")
        placeholder(name, arguments, vault)?.let { return ParsedCall.Invalid(it) }
        return try {
            ParsedCall.Valid(read(name, Reader(arguments, table)))
        } catch (bad: Bad) {
            ParsedCall.Invalid(bad.reason)
        }
    }

    /**
     * Why a placeholder cannot stand in [arguments]: one that [vault] does not hold, which the model made up, or one that
     * it holds outside the argument of [PLACEHOLDER_ARGUMENTS] where Kestrel can type or speak it; null when none.
     */
    private fun placeholder(name: String, arguments: JsonObject, vault: Vault): String? {
        fun strings(element: JsonElement): List<String> = when (element) {
            is JsonPrimitive -> if (element.isString) listOf(element.content) else emptyList()
            is JsonArray -> element.flatMap(::strings)
            is JsonObject -> element.values.flatMap(::strings)
        }
        for ((key, value) in arguments) {
            for (text in strings(value)) {
                Vault.PLACEHOLDER.findAll(text).map { it.value }.firstOrNull { vault.valueOf(it) == null }?.let { return "Kestrel has no $it in this task." }
                if (PLACEHOLDER_ARGUMENTS[name] != key) vault.placeholdersIn(text).firstOrNull()?.let { return "Kestrel can't use $it there." }
            }
        }
        return null
    }

    /** The one argument of each tool where a placeholder may stand: typed after the owner's yes, or spoken as its kind. */
    private val PLACEHOLDER_ARGUMENTS = mapOf(Tools.TYPE_TEXT to "text", Tools.SAY to "text", Tools.FINISH to "summary", Tools.FAIL to "reason")

    private fun read(name: String, r: Reader): AgentCall = when (name) {
        Tools.TAP -> AgentCall.Tap(r.target(), reason(r), category(r))
        Tools.LONG_PRESS -> AgentCall.LongPress(r.target(pressed = true), reason(r), category(r))
        Tools.TYPE_TEXT -> AgentCall.TypeText(r.target(typed = true), textOf(r), reason(r), category(r))
        Tools.SCROLL -> AgentCall.Scroll(r.choice("direction", DIRECTIONS), r.optionalTarget(pressed = true), reason(r), category(r))
        Tools.SWIPE -> AgentCall.Swipe(r.choice("direction", DIRECTIONS), r.optionalTarget(pressed = true), reason(r), category(r))
        Tools.BACK -> AgentCall.Back(reason(r))
        Tools.HOME -> AgentCall.Home(reason(r))
        Tools.OPEN_APP -> AgentCall.OpenApp(r.string("name"), reason(r))
        Tools.OPEN_URL -> AgentCall.OpenUrl(r.string("url"), r.optionalChoice("browser", BROWSERS) ?: Browser.DEFAULT, reason(r))
        Tools.OPEN_DEEP_LINK -> AgentCall.OpenDeepLink(r.string("uri"), reason(r), r.optionalString("app"))
        Tools.OPEN_SETTINGS -> AgentCall.OpenSettings(r.choice("page", PAGES), reason(r))
        Tools.WAIT -> AgentCall.Wait(r.int("seconds", 1..Tools.WAIT_MAX), reason(r))
        Tools.LOOK_CLOSER -> AgentCall.LookCloser(r.optionalTarget(), reason(r))
        Tools.FIND_CONTACT -> AgentCall.FindContact(r.string("name"))
        Tools.FIND_MEDIA -> AgentCall.FindMedia(day(r, "day")!!, day(r, "until"), r.optionalChoice("kinds", KINDS) ?: MediaKinds.BOTH)
        Tools.SHARE_MEDIA -> AgentCall.ShareMedia(r.string("app"), items(r))
        Tools.LIST_EVENTS -> AgentCall.ListEvents(day(r, "from")!!, day(r, "to")!!)
        Tools.CREATE_EVENT -> AgentCall.CreateEvent(r.string("title"), dateTime(r, "start")!!, dateTime(r, "end"), r.optionalString("location"))
        Tools.SET_ALARM -> AgentCall.SetAlarm(r.int("hour", 0..23), r.int("minute", 0..59), r.optionalString("label"), days(r))
        Tools.SET_TIMER -> AgentCall.SetTimer(r.int("seconds", 1..Tools.TIMER_MAX), r.optionalString("label"))
        Tools.LIST_NOTIFICATIONS -> AgentCall.ListNotifications
        Tools.LIST_SMS -> AgentCall.ListSms(r.optionalString("from"), r.optionalInt("limit", 1..Tools.LIST_MAX) ?: Tools.LIST_MAX)
        Tools.COMPOSE_SMS -> AgentCall.ComposeSms(r.string("number"), r.string("text"))
        Tools.LIST_CALLS -> AgentCall.ListCalls(r.optionalInt("limit", 1..Tools.LIST_MAX) ?: Tools.LIST_MAX)
        Tools.GET_LOCATION -> AgentCall.GetLocation
        Tools.MEDIA_CONTROL -> AgentCall.MediaControl(r.choice("action", MEDIA))
        Tools.NAVIGATE -> AgentCall.Navigate(r.choice("app", NAVIGATION), r.string("to"), r.optionalChoice("mode", MODES))
        Tools.ASK_USER -> AgentCall.AskUser(r.string("question", Tools.QUESTION_LIMIT))
        Tools.SAY -> AgentCall.Say(r.string("text", Tools.SAY_LIMIT))
        Tools.FINISH -> AgentCall.Finish(r.string("summary", Tools.SUMMARY_LIMIT), evidence(r), r.optionalString("signature"), notes(r))
        Tools.FAIL -> AgentCall.Fail(r.string("reason", Tools.FAIL_LIMIT))
        Tools.REMEMBER -> AgentCall.Remember(r.string("key"), r.string("value"))
        Tools.SET_SLIDER -> AgentCall.SetSlider(r.target(slider = true), r.int("percent", 0..100), reason(r), category(r))
        Tools.ADD_CONTACT -> AgentCall.AddContact(r.string("name", Tools.NAME_LIMIT), number(r))
        Tools.DELETE_CONTACT -> AgentCall.DeleteContact(r.int("contact", 1..Tools.LIST_MAX))
        else -> AgentCall.Forget(r.string("key"))
    }

    private fun reason(r: Reader): String = r.string("reason", Tools.REASON_LIMIT)

    /** add_contact's number: digits with a leading `+`, spaces, dashes and brackets, 3 to 20 digits (design D19 of sub-project 6). */
    private fun number(r: Reader): String {
        val text = r.string("number", Tools.NUMBER_LIMIT).trim()
        if (!PHONE.matches(text) || text.count { it in '0'..'9' } !in 3..20) throw Bad("The argument number must be a phone number of 3 to 20 digits.")
        return text
    }

    private val PHONE = Regex("\\+?[0-9 ()\\-]+")

    private fun category(r: Reader): Category = r.choice("category", CATEGORIES)

    /** `type_text`'s text may be empty, which clears the field. */
    private fun textOf(r: Reader): String {
        val element = r.arguments["text"]?.takeUnless { it is JsonNull } ?: throw Bad("The argument text is missing.")
        if (element !is JsonPrimitive || !element.isString) throw Bad("The argument text must be a string.")
        return element.content
    }

    private fun day(r: Reader, name: String): String? {
        val text = r.optionalString(name) ?: return if (name == "until") null else throw Bad("The argument $name is missing.")
        if (Dates.day(text, ANY_DAY) == null) throw Bad("The argument $name must be today, yesterday or YYYY-MM-DD.")
        return text
    }

    private fun dateTime(r: Reader, name: String): String? {
        val text = r.optionalString(name) ?: return if (name == "end") null else throw Bad("The argument $name is missing.")
        if (Dates.dateTime(text) == null) throw Bad("The argument $name must be YYYY-MM-DDTHH:MM.")
        return text
    }

    private fun items(r: Reader): List<String> {
        val items = r.array("items") ?: throw Bad("The argument items is missing.")
        if (items.isEmpty()) throw Bad("The argument items is empty.")
        return items.map { item ->
            val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (text == null || !ITEM.matches(text)) throw Bad("The argument items must hold item numbers such as m1.")
            text
        }
    }

    private fun days(r: Reader): List<DayOfWeek> = r.array("days").orEmpty().map { day ->
        val text = (day as? JsonPrimitive)?.takeIf { it.isString }?.content
        DAYS[text] ?: throw Bad("The argument days must hold: ${Tools.DAYS.joinToString(", ")}.")
    }

    private fun evidence(r: Reader): List<Evidence> {
        val items = r.array("evidence") ?: throw Bad("The argument evidence is missing.")
        return items.map { item ->
            val fields = Reader(item as? JsonObject ?: throw Bad("Each item of evidence must be an object."), r.table)
            val target = fields.optionalTarget()
            val step = fields.optionalInt("step", 1..Int.MAX_VALUE)
            if ((target == null) == (step == null)) throw Bad("Each item of evidence needs a target or a step.")
            Evidence(target, step, fields.optionalBoolean("checked"), fields.optionalInt("percent", 0..100))
        }
    }

    private fun notes(r: Reader): List<NoteDraft> {
        val notes = r.array("notes").orEmpty().map { item ->
            val fields = Reader(item as? JsonObject ?: throw Bad("Each note must be an object."), r.table)
            NoteDraft(fields.string("app"), fields.string("note", Tools.NOTE_LIMIT))
        }
        if (notes.groupingBy { it.app.trim().lowercase() }.eachCount().values.any { it > Tools.NOTES_PER_APP }) {
            throw Bad("At most ${Tools.NOTES_PER_APP} notes per app.")
        }
        return notes
    }
}
