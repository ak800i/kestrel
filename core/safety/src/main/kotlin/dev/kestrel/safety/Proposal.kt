package dev.kestrel.safety

/**
 * The ten rows of spec 12.3's table, in their order (design D13 of sub-project 5): a later row is more restrictive, so
 * the action's category is the highest that any source suggests. [wire] is the name in the tools' `category` enum.
 */
enum class Category(val wire: String) {
    READING("reading"),
    TYPING_REQUEST("typing_request"),
    ORDINARY_SETTINGS("ordinary_settings"),
    TYPING_SCREEN_TEXT("typing_screen_text"),
    SENDING("sending"),
    CALLS("calls"),
    DELETING("deleting"),
    PAYMENTS("payments"),
    CREDENTIALS("credentials"),
    PROTECTED("protected"),
    ;

    companion object {
        fun fromWire(name: String): Category? = entries.firstOrNull { it.wire == name }
    }
}

/** What kind of action a proposal is; each tool maps to one (design D10 and D13 of sub-project 5). */
enum class ActionKind {
    TAP,
    LONG_PRESS,
    TAP_AT,
    TYPE,
    SCROLL,
    SWIPE,

    /** `set_slider`, an action on an element whose category the model declares (design D19 of sub-project 6). */
    SET_SLIDER,
    BACK,
    HOME,
    WAIT,
    OPEN_APP,
    OPEN_URL,
    OPEN_DEEP_LINK,
    OPEN_SETTINGS,
    LOOK_CLOSER,

    /** `find_*`, `list_*` and `get_location`. */
    READ_DATA,

    /** `set_alarm`, `set_timer`, `create_event`, `media_control` and `navigate`. */
    SETTINGS_DATA,
    SHARE_MEDIA,
    COMPOSE_SMS,

    /** `add_contact`: typing the request, or typing screen text (design D19 of sub-project 6). */
    ADD_CONTACT,

    /** `delete_contact`: deleting (design D19 of sub-project 6). */
    DELETE_CONTACT,

    /** `ask_user`, `say`, `finish`, `fail` and `update_checklist`, which only report to the owner and never act. */
    DIALOGUE,

    /** `remember` and `forget`, which always ask the owner themselves (spec 11.4). */
    MEMORY,
}

/** A point on the screen, in pixels. */
data class Point(val x: Int, val y: Int)

/** What `share_media` shares: how many photos and videos. */
data class ShareCount(val photos: Int, val videos: Int)

/**
 * An action that the agent or a routine proposes (design D13 of sub-project 5). [targetKey] names an element of the
 * snapshot that is checked with it; [point] is a `TapAt` in screen pixels, with [pointText], its OCR line; [text] is
 * `type_text`'s text with placeholders, never a vault value; [link] is `open_url`'s or `open_deep_link`'s link, which came
 * from the owner's request or memory when [linkFromOwner] is true, and [linkTargetPackage] is the package of the activity
 * that `:app` resolved for it; [receivingPackage] is the app that `share_media` or `compose_sms` opens. The launcher
 * labels come from `AppsPort`, because `:core:safety` cannot read them; null makes the question name the app by its
 * package's last part. Its text form prints only kinds and lengths.
 */
data class Proposal(
    val tool: String,
    val kind: ActionKind,
    val declared: Category? = null,
    val targetKey: String? = null,
    val point: Point? = null,
    val pointText: String? = null,
    val text: String? = null,
    val link: String? = null,
    val linkFromOwner: Boolean = false,
    val linkTargetPackage: String? = null,
    val receivingPackage: String? = null,
    val shareCount: ShareCount? = null,
    val frontAppLabel: String? = null,
    val receivingAppLabel: String? = null,
    /** `set_slider`'s percent, which its question names (design D19 of sub-project 6). */
    val percent: Int? = null,
    /** The contact that `add_contact` adds or `delete_contact` deletes, by its name and its (first) number. */
    val contactName: String? = null,
    val contactNumber: String? = null,
) {
    override fun toString(): String =
        "Proposal(tool=$tool, kind=$kind, declared=$declared, target=${targetKey != null}, point=${point != null}, " +
            "textLength=${text?.length ?: 0}, linkLength=${link?.length ?: 0}, percent=$percent, contact=${contactName != null})"
}
