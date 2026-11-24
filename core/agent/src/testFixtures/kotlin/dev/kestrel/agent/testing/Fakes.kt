package dev.kestrel.agent.testing

import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.memory.RouteNote
import dev.kestrel.agent.memory.Routine
import dev.kestrel.agent.port.ActionOutcome
import dev.kestrel.agent.port.AgentClock
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.AppsPort
import dev.kestrel.agent.port.Badge
import dev.kestrel.agent.port.Capture
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.EyesPort
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.agent.port.LauncherApp
import dev.kestrel.agent.port.MediaAction
import dev.kestrel.agent.port.MediaKinds
import dev.kestrel.agent.port.MemoryStore
import dev.kestrel.agent.port.NavigationApp
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.port.ScreenPort
import dev.kestrel.agent.port.TaskLog
import dev.kestrel.agent.port.TravelMode
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.Part
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.HandOver
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A screen that the tests script (design D27): it starts at one snapshot and moves to another when an action matches one
 * of its rules; a `SetText` without a rule writes the text into its field. It records every action. A null snapshot is a
 * service that is not connected.
 */
class FakeScreen(first: ScreenSnapshot?) : ScreenPort {
    private class Rule(val matches: (ScreenAction, ScreenSnapshot) -> Boolean, val next: (ScreenSnapshot) -> ScreenSnapshot?)

    private val rules = mutableListOf<Rule>()
    var current: ScreenSnapshot? = first
    val actions = mutableListOf<ScreenAction>()
    val badges = mutableListOf<List<Badge>>()
    var resolver: (IntentSpec) -> String? = { it.packageName }
    var outcome: (ScreenAction) -> ActionOutcome = { ActionOutcome(done = true, method = "fake") }

    /** Runs before each snapshot is read: a test may change the screen here, as an app that draws its next window late. */
    var onSnapshot: () -> Unit = {}

    /** After an action that [matches] on the current screen, the screen becomes what [next] makes of it. */
    fun on(matches: (ScreenAction, ScreenSnapshot) -> Boolean, next: (ScreenSnapshot) -> ScreenSnapshot?) {
        rules += Rule(matches, next)
    }

    /** A tap or long press on the element labelled [label] shows [next]. */
    fun onTap(label: String, next: ScreenSnapshot) = on({ action, screen ->
        val key = (action as? ScreenAction.Tap)?.key ?: (action as? ScreenAction.LongPress)?.key
        key != null && screen.element(key)?.label == label
    }) { next }

    /** Opening anything (an app, a link, an intent) shows [next]. */
    fun onOpen(next: ScreenSnapshot) = on({ action, _ ->
        action is ScreenAction.OpenApp || action is ScreenAction.OpenUri || action is ScreenAction.OpenIntent
    }) { next }

    override suspend fun snapshot(): ScreenSnapshot? {
        onSnapshot()
        return current
    }

    override suspend fun act(action: ScreenAction): ActionOutcome {
        actions += action
        val result = outcome(action)
        if (!result.done) return result
        val screen = current ?: return result
        val rule = rules.firstOrNull { it.matches(action, screen) }
        current = when {
            rule != null -> rule.next(screen)
            action is ScreenAction.SetText -> typed(screen, action.key, action.text)
            action is ScreenAction.SetSlider -> screen.copy(elements = screen.elements.map { if (it.key == action.key) it.copy(sliderPercent = action.percent) else it })
            else -> screen
        }
        return result
    }

    override suspend fun resolve(intent: IntentSpec): String? = resolver(intent)

    override suspend fun showBadges(badges: List<Badge>) {
        this.badges += badges
    }

    override suspend fun hideBadges() = Unit

    override suspend fun waitForFront(packageName: String?, millis: Long, before: String?): Boolean = true

    override fun toString(): String = "FakeScreen(actions=${actions.size})"

    companion object {
        /** [screen] with [text] in the field with [key], as Android shows a typed field. */
        fun typed(screen: ScreenSnapshot, key: String, text: String): ScreenSnapshot = screen.copy(
            elements = screen.elements.map { if (it.key == key) it.copy(text = text, label = text.ifEmpty { it.hint }) else it },
        )
    }
}

/** The owner as the tests script him (design D27): queues of answers, and records of what Kestrel said and asked. */
class FakeOwner : OwnerPort {
    val answers = ArrayDeque<Answer>()
    val confirms = ArrayDeque<ConfirmEnd>()
    val handOverEnds = ArrayDeque<HandOverEnd>()
    val permissionEnds = ArrayDeque<PermissionEnd>()
    val gates = ArrayDeque<Gate>()
    val said = mutableListOf<String>()
    val asked = mutableListOf<Pair<String, AnswerKind>>()
    val confirmations = mutableListOf<Confirmation>()
    val handOvers = mutableListOf<HandOver>()
    val permissions = mutableListOf<List<String>>()

    /** Runs when a confirmation is asked, before its answer: a test may cancel the task's job here (a touch). */
    var onConfirm: suspend (Confirmation) -> Unit = {}

    /** Runs after Kestrel spoke: a test may change the screen here. */
    var onSay: suspend (String) -> Unit = {}

    /** Runs when a question is asked, before its answer: a test may change the screen here. */
    var onAsk: suspend (String) -> Unit = {}

    override suspend fun say(text: String) {
        said += text
        onSay(text)
    }

    override suspend fun ask(question: String, kind: AnswerKind): Answer {
        asked += question to kind
        onAsk(question)
        return answers.removeFirstOrNull() ?: Answer.Silence
    }

    override suspend fun confirm(confirmation: Confirmation): ConfirmEnd {
        confirmations += confirmation
        onConfirm(confirmation)
        return confirms.removeFirstOrNull() ?: ConfirmEnd.NO
    }

    override suspend fun handOver(handOver: HandOver): HandOverEnd {
        handOvers += handOver
        return handOverEnds.removeFirstOrNull() ?: HandOverEnd.CANCELLED
    }

    override suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd {
        this.permissions += permissions
        return permissionEnds.removeFirstOrNull() ?: PermissionEnd.REFUSED
    }

    override suspend fun gate(): Gate = gates.removeFirstOrNull() ?: Gate.GO

    override fun toString(): String = "FakeOwner(asked=${asked.size}, confirmations=${confirmations.size})"
}

/** The data tools as the tests script them: one answer per tool, the next answer first when a queue holds one. */
class FakeData : DataPort {
    val results = mutableMapOf<String, ArrayDeque<DataResult>>()
    val calls = mutableListOf<String>()

    /** The name that `find_contact` and the place that `navigate` got, in order. */
    val names = mutableListOf<String>()
    var forgotten = false

    /** Runs on every call, before its answer: a test may change the screen here, as an intent would. */
    var onCall: (String) -> Unit = {}

    fun answer(tool: String, vararg results: DataResult) {
        this.results.getOrPut(tool) { ArrayDeque() } += results
    }

    private fun next(tool: String): DataResult {
        calls += tool
        onCall(tool)
        val queue = results[tool] ?: return DataResult.Failed("the test gave no answer")
        return if (queue.size > 1) queue.removeFirst() else queue.firstOrNull() ?: DataResult.Failed("the test gave no answer")
    }

    override suspend fun findContact(name: String) = next("find_contact").also { names += name }
    override suspend fun findMedia(from: LocalDate, until: LocalDate, kinds: MediaKinds) = next("find_media")
    override suspend fun shareMedia(packageName: String, items: List<String>) = next("share_media")
    override suspend fun listEvents(from: LocalDate, to: LocalDate) = next("list_events")
    override suspend fun createEvent(title: String, start: LocalDateTime, end: LocalDateTime?, location: String?) = next("create_event")
    override suspend fun setAlarm(hour: Int, minute: Int, label: String?, days: List<DayOfWeek>) = next("set_alarm")
    override suspend fun setTimer(seconds: Int, label: String?) = next("set_timer")
    override suspend fun listNotifications() = next("list_notifications")
    override suspend fun listSms(from: String?, limit: Int) = next("list_sms")
    override suspend fun composeSms(number: String, text: String) = next("compose_sms")
    override suspend fun listCalls(limit: Int) = next("list_calls")
    override suspend fun getLocation() = next("get_location")
    override suspend fun mediaControl(action: MediaAction) = next("media_control")
    override suspend fun navigate(app: NavigationApp, to: String, mode: TravelMode?) = next("navigate").also { names += to }

    override fun forgetTask() {
        forgotten = true
    }

    override fun toString(): String = "FakeData(calls=${calls.size})"
}

class FakeApps(val apps: List<LauncherApp> = DEFAULT) : AppsPort {
    override suspend fun launcherApps(): List<LauncherApp> = apps

    override suspend fun label(packageName: String): String? = apps.firstOrNull { it.packageName == packageName }?.label

    override fun toString(): String = "FakeApps(${apps.size})"

    companion object {
        val DEFAULT = listOf(
            LauncherApp("Viber", "com.viber.voip", "26.1"),
            LauncherApp("Telegram", "org.telegram.messenger", "12.2"),
            LauncherApp("Gmail", "com.google.android.gm", "2026.11"),
            LauncherApp("Settings", "com.android.settings", "17"),
            LauncherApp("Waze", "com.waze", "5.12"),
            LauncherApp("Firefox", "org.mozilla.firefox", "160.0"),
            LauncherApp("Kestrel fixtures", "dev.kestrel.testapp", "1.0"),
            LauncherApp("Kestrel", "dev.kestrel", "0.5"),
        )
    }
}

class FakeMemory : MemoryStore {
    val facts = mutableListOf<Fact>()
    val notes = mutableMapOf<String, List<RouteNote>>()
    val found = mutableListOf<String>()
    val approvals = mutableSetOf<Pair<String, String>>()
    val routines = mutableListOf<Routine>()
    val signatures = mutableMapOf<String, Int>()

    override suspend fun facts(): List<Fact> = facts.toList()

    override suspend fun saveFact(fact: Fact) {
        facts.removeAll { it.key == fact.key }
        facts += fact
    }

    override suspend fun deleteFact(key: String): Boolean = facts.removeAll { it.key == key }

    override suspend fun notes(packageName: String): List<RouteNote> = notes[packageName].orEmpty()

    override suspend fun saveNotes(packageName: String, notes: List<RouteNote>) {
        this.notes[packageName] = notes
    }

    override suspend fun addFoundContacts(names: List<String>) {
        found += names
    }

    override suspend fun screenshotApproved(packageName: String, providerId: String): Boolean = (packageName to providerId) in approvals

    override suspend fun approveScreenshots(packageName: String, providerId: String) {
        approvals += packageName to providerId
    }

    override suspend fun routines(): List<Routine> = routines.toList()

    override suspend fun saveRoutine(routine: Routine) {
        routines.removeAll { it.name == routine.name }
        routines += routine
    }

    override suspend fun countSignature(signature: String): Int = (signatures.getOrDefault(signature, 0) + 1).also { signatures[signature] = it }

    override fun toString(): String = "FakeMemory(facts=${facts.size}, routines=${routines.size})"
}

class FakeLog : TaskLog {
    val entries = mutableListOf<Pair<TaskLog.Kind, String>>()

    override suspend fun write(kind: TaskLog.Kind, text: String) {
        entries += kind to text
    }

    fun texts(kind: TaskLog.Kind): List<String> = entries.filter { it.first == kind }.map { it.second }

    override fun toString(): String = "FakeLog(${entries.size})"
}

/** Kestrel's clock in the tests: the time moves only when a test moves it, or by [step] on every reading. */
class FakeClock(var step: Long = 0) : AgentClock {
    var elapsed = 0L
    var time: ZonedDateTime = ZonedDateTime.of(2026, 11, 20, 10, 20, 0, 0, ZoneId.of("Europe/Belgrade"))

    override fun elapsedMillis(): Long = elapsed.also { elapsed += step }

    override fun now(): ZonedDateTime = time

    override fun toString(): String = "FakeClock(elapsed=$elapsed)"
}

/**
 * Kestrel's eyes in the tests: a capture of [width] × [height], the given OCR lines, and a fixed JPEG. [captures] counts
 * the captures; [onRead] runs on every OCR pass, before its lines: a test may change the screen here.
 */
class FakeEyes(var lines: List<OcrLine>? = emptyList(), var capture: Capture? = null) : EyesPort {
    var closed = 0
    var captures = 0
    var onRead: suspend () -> Unit = {}
    val encoded = mutableListOf<Pair<List<IntRect>, List<Badge>>>()

    /** The area of each call of [encode], null for the whole capture. */
    val areas = mutableListOf<IntRect?>()

    override suspend fun capture(): Capture = (capture ?: Capture.Captured(
        image = object : Capture.Image {
            override val width = 1080
            override val height = 2400

            override fun close() {
                closed++
            }
        },
        originX = 0,
        originY = 0,
        appPixels = listOf(IntRect(0, 0, 1080, 2400)),
        blocked = emptyList(),
    )).also { captures++ }

    override suspend fun read(capture: Capture.Captured, regions: List<IntRect>, exclude: List<IntRect>): List<OcrLine>? {
        onRead()
        return lines
    }

    override suspend fun encode(capture: Capture.Captured, masks: List<IntRect>, marks: List<Badge>, area: IntRect?): Part.Image {
        encoded += masks to marks
        areas += area
        return Part.Image(ImageType.JPEG, byteArrayOf(1, 2, 3))
    }

    /** The lines that each call of [once] got; [merge] gives its result. */
    val merged = mutableListOf<List<OcrLine>>()
    var merge: (List<OcrLine>) -> List<OcrLine> = { it }

    override fun once(lines: List<OcrLine>): List<OcrLine> {
        merged += lines
        return merge(lines)
    }

    override fun toString(): String = "FakeEyes"
}
