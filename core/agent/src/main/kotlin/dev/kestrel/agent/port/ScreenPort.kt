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

/** An intent that a data tool or an `open_*` tool sends (design D1, D11, D12), with its string extras. */
data class IntentSpec(
    val action: String,
    val data: String? = null,
    val packageName: String? = null,
    val browsable: Boolean = false,
    val extras: Map<String, String> = emptyMap(),
) {
    override fun toString(): String =
        "IntentSpec(action=$action, data=${data?.length ?: 0} chars, package=${packageName != null}, browsable=$browsable, extras=${extras.size})"
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

    data class OpenApp(val packageName: String) : ScreenAction {
        override fun toString(): String = "OpenApp(package=${packageName.length} chars)"
    }

    data class OpenUri(val uri: String, val packageName: String?, val browsable: Boolean) : ScreenAction {
        override fun toString(): String = "OpenUri(uri=${uri.length} chars, package=${packageName != null}, browsable=$browsable)"
    }

    data class OpenIntent(val spec: IntentSpec) : ScreenAction
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
