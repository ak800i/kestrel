package dev.kestrel.agent.tools

import dev.kestrel.agent.memory.RouteNote
import dev.kestrel.providers.chat.ToolSpec
import dev.kestrel.safety.Category
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The agent's tools (design D10 to D12 of sub-project 5; spec 11.4), in one fixed order, byte for byte the same in every
 * request, so that the prompt cache holds (r51). The schemas stay within spec 10.3's subset, which `ToolSpec` checks;
 * the subset has no minimum, maximum or length, so the limits are written into the descriptions and `ToolArguments`
 * checks them. Only the five tools that act on an element declare a `category`; code fixes the others' (design D13).
 */
object Tools {
    const val TAP = "tap"
    const val LONG_PRESS = "long_press"
    const val TYPE_TEXT = "type_text"
    const val SCROLL = "scroll"
    const val SWIPE = "swipe"
    const val BACK = "back"
    const val HOME = "home"
    const val OPEN_APP = "open_app"
    const val OPEN_URL = "open_url"
    const val OPEN_DEEP_LINK = "open_deep_link"
    const val OPEN_SETTINGS = "open_settings"
    const val WAIT = "wait"
    const val LOOK_CLOSER = "look_closer"
    const val FIND_CONTACT = "find_contact"
    const val FIND_MEDIA = "find_media"
    const val SHARE_MEDIA = "share_media"
    const val LIST_EVENTS = "list_events"
    const val CREATE_EVENT = "create_event"
    const val SET_ALARM = "set_alarm"
    const val SET_TIMER = "set_timer"
    const val LIST_NOTIFICATIONS = "list_notifications"
    const val LIST_SMS = "list_sms"
    const val COMPOSE_SMS = "compose_sms"
    const val LIST_CALLS = "list_calls"
    const val GET_LOCATION = "get_location"
    const val MEDIA_CONTROL = "media_control"
    const val NAVIGATE = "navigate"
    const val ASK_USER = "ask_user"
    const val SAY = "say"
    const val FINISH = "finish"
    const val FAIL = "fail"
    const val REMEMBER = "remember"
    const val FORGET = "forget"
    const val SET_SLIDER = "set_slider"
    const val ADD_CONTACT = "add_contact"
    const val DELETE_CONTACT = "delete_contact"

    /** The tools whose call declares a category: those that act on an element. */
    val WITH_CATEGORY: Set<String> = setOf(TAP, LONG_PRESS, TYPE_TEXT, SCROLL, SWIPE, SET_SLIDER)

    internal const val REASON_LIMIT = 120
    internal const val QUESTION_LIMIT = 200
    internal const val SAY_LIMIT = 600
    internal const val SUMMARY_LIMIT = 300
    internal const val FAIL_LIMIT = 200
    internal const val NOTE_LIMIT = 200
    internal const val NOTES_PER_APP = 3
    internal const val WAIT_MAX = 10
    internal const val LIST_MAX = 20
    internal const val TIMER_MAX = 86_400
    internal const val NAME_LIMIT = 80
    internal const val NUMBER_LIMIT = 40

    internal val DIRECTIONS = listOf("up", "down", "left", "right")
    internal val DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    private fun string(description: String, values: List<String>? = null): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive(description))
            if (values != null) put("enum", JsonArray(values.map(::JsonPrimitive)))
        },
    )

    private fun integer(description: String): JsonObject = JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive(description)))

    private fun boolean(description: String): JsonObject = JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive(description)))

    private fun array(description: String, items: JsonObject): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("array"), "description" to JsonPrimitive(description), "items" to items))

    private fun objectOf(properties: List<Pair<String, JsonObject>>, required: List<String>, description: String? = null): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive("object"))
            if (description != null) put("description", JsonPrimitive(description))
            put("properties", JsonObject(properties.toMap()))
            if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        },
    )

    private val target = "target" to integer("The number of an element in the newest observation.")
    private val optionalTarget = "target" to integer("The number of an element in the newest observation; leave it out for the whole screen.")
    private val reason = "reason" to string("Why this action, in a few words; at most $REASON_LIMIT characters. Logged, never spoken.")
    private val category = "category" to string(
        "The kind of this action: reading, typing the owner's request, ordinary settings, typing text from the screen, sending, " +
            "calls, deleting, payments, credentials or protected settings. Kestrel's code checks it.",
        Category.entries.map { it.wire },
    )
    private val direction = "direction" to string("Which way to move the content's view: up, down, left or right.", DIRECTIONS)

    private fun tool(name: String, description: String, properties: List<Pair<String, JsonObject>>, required: List<String>) =
        ToolSpec(name, description, objectOf(properties, required))

    val ALL: List<ToolSpec> = listOf(
        tool(TAP, "Tap an element of the newest observation; an OCR line is tapped at its centre.", listOf(target, reason, category), listOf("target", "reason", "category")),
        tool(LONG_PRESS, "Long-press an element of the newest observation.", listOf(target, reason, category), listOf("target", "reason", "category")),
        tool(
            TYPE_TEXT,
            "Replace the whole text of an editable field with the given text. A placeholder such as [OTP_1] is typed as its real value only after the owner's yes.",
            listOf(target, "text" to string("The field's whole new text."), reason, category),
            listOf("target", "text", "reason", "category"),
        ),
        tool(
            SCROLL,
            "Scroll an element, or the largest scrollable element of the screen, to show more content in the given direction: down shows what is below.",
            listOf(direction, optionalTarget, reason, category),
            listOf("direction", "reason", "category"),
        ),
        tool(
            SWIPE,
            "Swipe across an element or the screen in the given direction. Left and right only on an element that scrolls sideways or on a screen without a list that scrolls up and down.",
            listOf(direction, optionalTarget, reason, category),
            listOf("direction", "reason", "category"),
        ),
        tool(BACK, "Press Android's Back.", listOf(reason), listOf("reason")),
        tool(HOME, "Go to the home screen.", listOf(reason), listOf("reason")),
        tool(OPEN_APP, "Open an installed app by its name.", listOf("name" to string("The app's name as the launcher shows it."), reason), listOf("name", "reason")),
        tool(
            OPEN_URL,
            "Open an https link in a browser. Use this to open web pages; never type into a browser's address bar.",
            listOf("url" to string("An https link."), "browser" to string("Which browser: default, firefox or chrome.", Browser.entries.map { it.wire }), reason),
            listOf("url", "reason"),
        ),
        tool(
            OPEN_DEEP_LINK,
            "Open a link into an app. Only these schemes: viber, whatsapp, tg, waze, google.navigation, geo, tel, sms and mailto. A tel link only opens the dial screen.",
            listOf("uri" to string("The link."), reason),
            listOf("uri", "reason"),
        ),
        tool(
            OPEN_SETTINGS,
            "Open a page of Android's settings.",
            listOf("page" to string("The settings page.", SettingsPage.entries.map { it.wire }), reason),
            listOf("page", "reason"),
        ),
        tool(
            WAIT,
            "Wait until the screen changes or the time runs out, whichever comes first.",
            listOf("seconds" to integer("How long to wait at most: 1 to $WAIT_MAX seconds."), reason),
            listOf("seconds", "reason"),
        ),
        tool(
            LOOK_CLOSER,
            "When the observation misses something that the screen shows: the first call reads text with OCR, the second may send a screenshot, the third asks the owner to pick a number.",
            listOf(optionalTarget, reason),
            listOf("reason"),
        ),
        tool(FIND_CONTACT, "Find contacts by name: their numbers, e-mail addresses and messaging apps.", listOf("name" to string("The name to look for.")), listOf("name")),
        tool(
            FIND_MEDIA,
            "Find photos and videos taken on a day or between two days. Returns item numbers such as m1, never the pictures.",
            listOf(
                "day" to string("today, yesterday or YYYY-MM-DD."),
                "until" to string("The last day: today, yesterday or YYYY-MM-DD; leave it out for one day."),
                "kinds" to string("photos, videos or both.", listOf("photos", "videos", "both")),
            ),
            listOf("day"),
        ),
        tool(
            SHARE_MEDIA,
            "Open an app's share screen with media items that find_media returned. The send itself happens on the screen.",
            listOf(
                "app" to string("telegram, whatsapp, viber, gmail or an installed app's name."),
                "items" to array("The item numbers, such as m1.", string("An item number.")),
            ),
            listOf("app", "items"),
        ),
        tool(
            LIST_EVENTS,
            "List calendar events between two days, at most $LIST_MAX.",
            listOf("from" to string("The first day: YYYY-MM-DD."), "to" to string("The last day: YYYY-MM-DD.")),
            listOf("from", "to"),
        ),
        tool(
            CREATE_EVENT,
            "Open the calendar app's screen for a new event with these details; saving it happens on the screen.",
            listOf(
                "title" to string("The event's title."),
                "start" to string("YYYY-MM-DDTHH:MM, local time."),
                "end" to string("YYYY-MM-DDTHH:MM, local time."),
                "location" to string("Where."),
            ),
            listOf("title", "start"),
        ),
        tool(
            SET_ALARM,
            "Set an alarm.",
            listOf(
                "hour" to integer("0 to 23."),
                "minute" to integer("0 to 59."),
                "label" to string("The alarm's label."),
                "days" to array("The days on which it repeats; leave it out for once.", string("A day.", DAYS)),
            ),
            listOf("hour", "minute"),
        ),
        tool(
            SET_TIMER,
            "Start a timer.",
            listOf("seconds" to integer("1 to $TIMER_MAX seconds."), "label" to string("The timer's label.")),
            listOf("seconds"),
        ),
        tool(LIST_NOTIFICATIONS, "List the notifications that show now, newest first, at most $LIST_MAX.", emptyList(), emptyList()),
        tool(
            LIST_SMS,
            "List text messages, newest first.",
            listOf("from" to string("A number or a contact's name; leave it out for all."), "limit" to integer("How many: 1 to $LIST_MAX.")),
            emptyList(),
        ),
        tool(
            COMPOSE_SMS,
            "Open the messages app with a text message to a number. The send itself happens on the screen.",
            listOf("number" to string("The phone number."), "text" to string("The message.")),
            listOf("number", "text"),
        ),
        tool(LIST_CALLS, "List recent calls, newest first.", listOf("limit" to integer("How many: 1 to $LIST_MAX.")), emptyList()),
        tool(GET_LOCATION, "Get the phone's location from satellites.", emptyList(), emptyList()),
        tool(
            MEDIA_CONTROL,
            "Control the media that plays.",
            listOf("action" to string("play, pause, next or previous.", listOf("play", "pause", "next", "previous"))),
            listOf("action"),
        ),
        tool(
            NAVIGATE,
            "Start navigation in Waze or Google Maps.",
            listOf(
                "app" to string("waze or google_maps.", listOf("waze", "google_maps")),
                "to" to string("home, work, coordinates as lat,lon, or an address."),
                "mode" to string("For Google Maps: drive, bicycle, walk or two_wheeler.", listOf("drive", "bicycle", "walk", "two_wheeler")),
            ),
            listOf("app", "to"),
        ),
        tool(
            ASK_USER,
            "Ask the owner a question that only he can answer, and wait for his spoken answer.",
            listOf("question" to string("The question; at most $QUESTION_LIMIT characters.")),
            listOf("question"),
        ),
        tool(SAY, "Tell the owner something.", listOf("text" to string("What to say; at most $SAY_LIMIT characters.")), listOf("text")),
        tool(
            FINISH,
            "End the task as done. Kestrel checks the evidence; a claim without it fails.",
            listOf(
                "summary" to string("What was done; at most $SUMMARY_LIMIT characters."),
                "evidence" to array(
                    "What shows that the task is done: elements of the newest observation, or the steps of data tools.",
                    objectOf(
                        listOf(
                            "target" to integer("The number of an element in the newest observation."),
                            "step" to integer("The step of a data tool's result, which its result line names: Result (step 5) is step 5."),
                            "checked" to boolean("For a checkable element: whether it should be checked."),
                            "percent" to integer("For a slider: the percent that it should now show."),
                        ),
                        emptyList(),
                    ),
                ),
                "signature" to string("The kind of task, such as messaging.send_text.viber."),
                "notes" to array(
                    "Notes for the next time in an app, at most $NOTES_PER_APP per app. A note is a path of fixed verbs and quoted labels " +
                        "joined by arrows, such as: open \"Chats\" → tap \"New chat\". The verbs are open, tap, long press, type into and " +
                        "scroll, each with the label of the element that it acts on, and back; at most ${RouteNote.MAX_STEPS} steps. Kestrel " +
                        "keeps only a path whose labels your own steps acted on.",
                    objectOf(listOf("app" to string("The app's name."), "note" to string("The path; at most $NOTE_LIMIT characters.")), listOf("app", "note")),
                ),
            ),
            listOf("summary", "evidence"),
        ),
        tool(FAIL, "End the task because it cannot be done.", listOf("reason" to string("Why; at most $FAIL_LIMIT characters.")), listOf("reason")),
        tool(
            REMEMBER,
            "Ask the owner to remember a fact, such as an alias.",
            listOf("key" to string("What the fact is about."), "value" to string("The fact.")),
            listOf("key", "value"),
        ),
        tool(FORGET, "Ask the owner to forget a fact.", listOf("key" to string("What the fact is about.")), listOf("key")),
        // New tools go at the end, so that the earlier tools keep their order and text (spec 11.4).
        tool(
            SET_SLIDER,
            "Put a slider of the newest observation at a percent of its range. Kestrel reads the value back.",
            listOf(
                target,
                "percent" to integer("Where to put the slider, in percent of its range: 0 is its lowest and 100 its highest."),
                reason,
                category,
            ),
            listOf("target", "percent", "reason", "category"),
        ),
        tool(
            ADD_CONTACT,
            "Add a contact with a name and a phone number to the phone's own contacts, which no online account syncs. Use it instead of a contacts app.",
            listOf(
                "name" to string("The contact's name; at most $NAME_LIMIT characters."),
                "number" to string("The phone number, with digits, spaces, dashes, brackets and a leading +; at most $NUMBER_LIMIT characters."),
            ),
            listOf("name", "number"),
        ),
        tool(
            DELETE_CONTACT,
            "Delete a contact that the task's newest find_contact result lists, by its number there: 2 for contact 2. Use it instead of a contacts app.",
            listOf("contact" to integer("The contact's number in the newest find_contact result.")),
            listOf("contact"),
        ),
    )

    private val byName = ALL.associateBy { it.name }

    fun spec(name: String): ToolSpec? = byName[name]
}
