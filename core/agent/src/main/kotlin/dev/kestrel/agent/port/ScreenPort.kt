package dev.kestrel.agent.port

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot

/**
 * The screen as the agent sees and moves it (design D1 of sub-project 5). `:app`'s `AppScreenPort` (plan 5B) implements
 * it with `ScreenSession` and `ScreenActions`; the tests use the fixtures' `FakeScreen`.
 */
interface ScreenPort {
    /** A settled snapshot, or null when the accessibility service is not running. */
    suspend fun snapshot(): ScreenSnapshot?

    suspend fun act(action: ScreenAction): ActionOutcome

    /** The package of the activity that would handle [intent] (`PackageManager.resolveActivity`), or null when none would. */
    suspend fun resolve(intent: IntentSpec): String?

    /**
     * The packages whose activities could handle [intent], from one query (`PackageManager.queryIntentActivities`);
     * Android's chooser is none of them. A package here takes [intent], and the launch check accepts it as the target of
     * an intent that names it, even when two of its activities take [intent]: Android then asks which of that app's
     * activities opens it, among that app's activities only.
     */
    suspend fun handlers(intent: IntentSpec): Set<String>

    suspend fun showBadges(badges: List<Badge>)

    suspend fun hideBadges()

    /**
     * Waits up to [millis] until [packageName] is in front and its window has an actionable element (design D11); with a
     * null [packageName], until any app other than [before] is in front. False when the time ran out.
     */
    suspend fun waitForFront(packageName: String?, millis: Long, before: String? = null): Boolean
}

enum class Direction { UP, DOWN, LEFT, RIGHT }

enum class GlobalKind { BACK, HOME }

/**
 * How often a tap may press (design D32 item 29): [ONCE] presses once and reports an uncertain result instead of
 * pressing again; [VERIFIED] presses a second time only when a fresh snapshot shows that the first press changed nothing.
 */
enum class Press { ONCE, VERIFIED }

/**
 * What the element of a key looked like in the observation that the model acted on (design D32 item 27): `:app` checks
 * the fresh snapshot's element against it before it acts, and does nothing when it does not fit. [label] is the raw
 * label that the snapshot had.
 */
data class Expect(
    val label: String?,
    val className: String?,
    val packageName: String?,
    val editable: Boolean,
    val password: Boolean,
    val checkable: Boolean,
) {
    override fun toString(): String =
        "Expect(label=${label?.length ?: 0} chars, class=${className != null}, package=${packageName != null}, editable=$editable, password=$password, checkable=$checkable)"
}

/**
 * An intent that Kestrel sends for the agent (design D1, D11, D12 of sub-project 5; plan 6B-basefix): the `open_*` tools'
 * links and pages and the data tools' app screens, as plain values that `:core:datatools`' `IntentSpecs.toIntent` turns
 * into Android's intent, so that the intent that the launch check resolves is the intent that opens. [type] is the
 * data's MIME type, [extras] are typed, and [readable] holds the content URIs that the app that opens it may read, which
 * go into the clip data with a read grant.
 */
data class IntentSpec(
    val action: String,
    val data: String? = null,
    val packageName: String? = null,
    val browsable: Boolean = false,
    val extras: Map<String, IntentExtra> = emptyMap(),
    val type: String? = null,
    val readable: List<String> = emptyList(),
) {
    /** A VIEW intent with data is a link, which LinkCheck judges wherever it comes from (spec 12.1). */
    val isLink: Boolean get() = action == VIEW && data != null

    override fun toString(): String =
        "IntentSpec(action=$action, data=${data?.length ?: 0} chars, package=${packageName != null}, browsable=$browsable, " +
            "extras=${extras.size}, type=${type != null}, readable=${readable.size})"

    companion object {
        const val VIEW = "android.intent.action.VIEW"
    }
}

/** A typed extra of an [IntentSpec]. Its `toString` names only its kind, because a value can be the owner's data. */
sealed interface IntentExtra {
    data class Text(val value: String) : IntentExtra {
        override fun toString(): String = "Text(${value.length} chars)"
    }

    data class Number(val value: Int) : IntentExtra {
        override fun toString(): String = "Number"
    }

    data class LongNumber(val value: Long) : IntentExtra {
        override fun toString(): String = "LongNumber"
    }

    data class Flag(val value: Boolean) : IntentExtra {
        override fun toString(): String = "Flag"
    }

    data class Numbers(val values: List<Int>) : IntentExtra {
        override fun toString(): String = "Numbers(${values.size})"
    }

    /** One content URI, as `EXTRA_STREAM` of a share holds it. */
    data class ContentUri(val uri: String) : IntentExtra {
        override fun toString(): String = "ContentUri"
    }

    data class ContentUris(val uris: List<String>) : IntentExtra {
        override fun toString(): String = "ContentUris(${uris.size})"
    }
}

/**
 * One action of the agent on the screen (design D1, D10). A press's [Tap.guard] is test mode's check, which `:app` runs
 * again on every snapshot that the press acts on, and the press does nothing when it says no (fix-5c-selfchats-review M1).
 */
sealed interface ScreenAction {
    data class Tap(val key: String, val expect: Expect, val press: Press, val guard: ((ScreenSnapshot) -> Boolean)? = null) : ScreenAction {
        override fun toString(): String = "Tap(key=${key.length} chars, press=$press, guard=${guard != null})"
    }

    data class LongPress(val key: String, val expect: Expect, val press: Press, val guard: ((ScreenSnapshot) -> Boolean)? = null) : ScreenAction {
        override fun toString(): String = "LongPress(key=${key.length} chars, press=$press, guard=${guard != null})"
    }

    /** A tap at a point, for an OCR line (design D20). */
    data class TapAt(val x: Int, val y: Int) : ScreenAction

    /** Sets the whole field to [text], a vault value only after the owner's yes (design D17). */
    data class SetText(val key: String, val expect: Expect, val text: String) : ScreenAction {
        override fun toString(): String = "SetText(key=${key.length} chars, text=${text.length} chars)"
    }

    data class Scroll(val key: String?, val direction: Direction, val expect: Expect?) : ScreenAction {
        override fun toString(): String = "Scroll(key=${key?.length ?: 0} chars, direction=$direction)"
    }

    data class Swipe(val key: String?, val direction: Direction, val expect: Expect?) : ScreenAction {
        override fun toString(): String = "Swipe(key=${key?.length ?: 0} chars, direction=$direction)"
    }

    /** Puts the slider at [percent] of its range and reads it back (design D19 of sub-project 6). */
    data class SetSlider(val key: String, val expect: Expect, val percent: Int) : ScreenAction {
        override fun toString(): String = "SetSlider(key=${key.length} chars, percent=$percent)"
    }

    data class Global(val kind: GlobalKind) : ScreenAction

    /** Opens what [launch] holds: the only action that opens an app, a link or a page (plan 6B-basefix). */
    data class Launch(val launch: CheckedLaunch) : ScreenAction
}

/**
 * What an action did: [done] when Android performed it, [uncertain] when a press may not have worked (design D32 item
 * 29), [method] the way that worked, and [reason] one of `:core:screen`'s fixed sentences when it failed.
 */
data class ActionOutcome(val done: Boolean, val uncertain: Boolean = false, val method: String? = null, val reason: String? = null) {
    override fun toString(): String = "ActionOutcome(done=$done, uncertain=$uncertain, method=$method, reason=${reason?.length ?: 0} chars)"
}

/** A number that the screen shows over an element for the owner (design D20's third step). */
data class Badge(val number: Int, val bounds: IntRect)
