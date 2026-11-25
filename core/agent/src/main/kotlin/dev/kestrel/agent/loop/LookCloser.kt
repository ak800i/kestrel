package dev.kestrel.agent.loop

import dev.kestrel.agent.observe.TableEntry
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.Badge
import dev.kestrel.agent.port.Capture
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.tools.AgentCall
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.providers.chat.Part
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSignature
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.screen.model.Signature
import kotlinx.coroutines.delay

/**
 * `look_closer`'s three steps (design D20; spec 11.6), one per call on the same screen: OCR where the tree has no text,
 * then a screenshot for the model after the owner's yes, then the numbers for the owner to pick from. A call with a
 * target limits OCR and the screenshot to that element. When a step cannot run here (no OCR files, a model without
 * images, the owner's no or no answer), the next one runs in the same call. Both questions go through the run's
 * [OwnerTalk], so an interrupted one is asked again. Captures stay in memory and are closed at once.
 */
internal class LookCloser(private val run: TaskRun) {
    private val calls = mutableMapOf<Signature, Int>()
    private var ocrMissing = false

    /** Why a capture gave no picture: a secure window, or Kestrel's fixed sentence of `Capture.NotNow`. */
    private class NoCapture(val secure: Boolean, val reason: String) : Exception(null, null, false, false)

    suspend fun step(call: AgentCall.LookCloser): Done {
        val target = call.target?.let(run.observation.table::entry)
        val signature = run.observation.signature
        val count = calls.getOrDefault(signature, 0)
        calls[signature] = count + 1
        return try {
            when (count) {
                0 -> ocr(call, target) ?: screenshot(call, target) ?: numbers(call)
                1 -> screenshot(call, target) ?: numbers(call)
                else -> numbers(call)
            }
        } catch (e: NoCapture) {
            // A secure screen goes on with the numbers next time; anything else may be tried again.
            calls[signature] = if (e.secure) 2 else count
            Done(call.tool, text = "Result: ${e.reason}", summary = "Step ${run.replies}: look_closer could not capture the screen.",
                last = "look_closer could not capture the screen", after = run.fresh(), progress = false)
        }
    }

    // Step 1 (D20; r61): OCR on the regions without tree text, never on password or data-sensitive elements; lines that
    // overlap tree text are dropped, and only lines with a mean score of at least 0.8 are kept, each of them once.
    // Null: no OCR files.
    private suspend fun ocr(call: AgentCall.LookCloser, target: TableEntry?): Done? {
        val snapshot = run.fresh()
        if (!run.frontCheck.capturable(snapshot)) return null
        val lines = withCapture { capture -> read(capture, snapshot, target) } ?: return null
        val kept = run.ports.eyes.once(lines.filter { it.score >= MIN_SCORE && !overlapsText(it, snapshot) })
        return Done(
            call.tool, target = target, after = snapshot, ocr = kept, progress = kept.isNotEmpty(),
            text = "Result: look_closer read ${kept.size} lines of text.",
            summary = "Step ${run.replies}: look_closer read ${kept.size} lines.",
            last = "look_closer read ${kept.size} lines",
        )
    }

    // Step 2 (D20): a screenshot for a model that takes images, after the owner's yes, with every secret masked. The
    // observation with step 1's OCR lines stays the newest, because the picture's numbers are its numbers.
    private suspend fun screenshot(call: AgentCall.LookCloser, target: TableEntry?): Done? {
        val asked = run.fresh()
        val front = ScreenTree.frontWindow(asked)?.packageName ?: return null
        if (!run.currentModel.takesImages || !run.frontCheck.capturable(asked)) return null
        val provider = run.ports.model.model
        if (!run.recalled(false) { run.ports.memory.screenshotApproved(front, provider.providerId) }) {
            val app = run.label(front) ?: front.substringAfterLast('.')
            val warning = if (ocrMissing) " I can't hide numbers that only the picture shows." else ""
            val answer = run.talk.ask("May I send a screenshot of $app to ${provider.providerName}?$warning", AnswerKind.SCREENSHOT)
            when (approval(answer)) {
                Approval.ALWAYS -> run.remembering { run.ports.memory.approveScreenshots(front, provider.providerId) }
                Approval.ONCE -> Unit
                Approval.NO -> return null
            }
        }
        // The owner may take a while to answer: a screen that changed since the question is never captured (spec 12.5).
        run.gate()
        repeat(CAPTURES) {
            val now = run.fresh()
            val same = ScreenTree.frontWindow(now)?.packageName == front && ScreenSignature.of(now).sameScreen(ScreenSignature.of(asked))
            if (!same || !run.frontCheck.capturable(now)) {
                return Done(call.tool, text = "Result: $CHANGED", summary = "Step ${run.replies}: look_closer took no screenshot; the screen changed.",
                    last = "look_closer took no screenshot; the screen changed", after = now, progress = false)
            }
            val shot = withCapture { capture -> shot(capture, now, target) } ?: return Done(call.tool, Outcome.Failed(NO_SCREENSHOT), target)
            val image = (shot as? Shot.Sent)?.image ?: return@repeat
            return Done(
                call.tool, target = target, screenshot = image, progress = false,
                text = "Result: the screenshot follows.",
                summary = "Step ${run.replies}: look_closer sent a screenshot.",
                last = "look_closer sent a screenshot",
            )
        }
        return Done(call.tool, text = "Result: $MOVED", summary = "Step ${run.replies}: look_closer took no screenshot; the screen moved.",
            last = "look_closer took no screenshot; the screen moved", after = run.fresh(), progress = false)
    }

    private sealed interface Shot {
        class Sent(val image: Part.Image) : Shot

        data object Moved : Shot
    }

    // The masks come from [now], read before the capture; when the masked parts are elsewhere after the capture and OCR,
    // such as a chat row that a new message pushed up, the picture may show a secret beside its black box (review 5C-A M2).
    // With a target, the picture holds only the target (D20), where OCR read every line for the masks. The tree shows a
    // move only some time after it (review 5C M1), so the tree is read again no sooner than MOVE_SEEN_MILLIS after the
    // capture; OCR usually takes longer than that.
    private suspend fun shot(capture: Capture.Captured, now: ScreenSnapshot, target: TableEntry?): Shot? {
        val capturedAt = run.ports.clock.elapsedMillis()
        val captured = IntRect(capture.originX, capture.originY, capture.originX + capture.image.width, capture.originY + capture.image.height)
        val area = target?.let { it.bounds.intersect(captured) ?: return null }
        val lines = read(capture, now, target).orEmpty()
        val masks = masks(now, lines)
        val left = MOVE_SEEN_MILLIS - (run.ports.clock.elapsedMillis() - capturedAt)
        if (left > 0) delay(left)
        if (masks(run.fresh(), lines).toSet() != masks.toSet()) return Shot.Moved
        return Shot.Sent(run.ports.eyes.encode(capture, masks, badges(), area))
    }

    // Step 3 (D20): the owner picks a number on the screen; at most four numbers are also the card's buttons (plan 7F).
    private suspend fun numbers(call: AgentCall.LookCloser): Done {
        run.ports.screen.showBadges(badges())
        try {
            val answer = run.talk.ask(WHICH, AnswerKind.PICK_NUMBER, choices(), highest())
            val picked = (answer as? Answer.Number)?.n?.takeIf { run.observation.table.entry(it) != null }
                ?: return Done(call.tool, Outcome.NoAnswer)
            return Done(call.tool, Outcome.Answered, extra = Redacted.kestrel("The owner picked [$picked]."))
        } finally {
            run.ports.screen.hideBadges()
        }
    }

    private suspend fun <T> withCapture(block: suspend (Capture.Captured) -> T?): T? =
        when (val capture = run.ports.eyes.capture()) {
            is Capture.Captured -> capture.image.use { block(capture) }
            Capture.Secure -> throw NoCapture(secure = true, reason = SECURE)
            is Capture.NotNow -> throw NoCapture(secure = false, reason = capture.reason)
        }

    private suspend fun read(capture: Capture.Captured, snapshot: ScreenSnapshot, target: TableEntry?): List<OcrLine>? {
        val window = ScreenTree.frontWindow(snapshot) ?: return emptyList()
        val regions = if (target != null) listOf(target.bounds) else textless(snapshot, window.id, window.bounds)
        val exclude = snapshot.elements.filter { it.password || it.dataSensitive }.map { it.bounds }
        val lines = run.ports.eyes.read(capture, regions, exclude)
        if (lines == null) ocrMissing = true
        return lines
    }

    // Actionable elements without a label, and large elements (1/16 of the window) whose subtree has no text (D20).
    private fun textless(snapshot: ScreenSnapshot, windowId: Int, area: IntRect): List<IntRect> {
        val elements = snapshot.elements
        val large = area.width.toLong() * area.height / LARGE_SHARE
        return elements.indices.filter { index ->
            val element = elements[index]
            if (element.windowId != windowId || element.password || element.dataSensitive) return@filter false
            val subtreeText = elements.indices.any { other ->
                !elements[other].label.isNullOrBlank() && (other == index || index in ScreenTree.ancestors(snapshot, other))
            }
            (element.isActionable && element.label.isNullOrBlank()) || (element.bounds.width.toLong() * element.bounds.height >= large && !subtreeText)
        }.map { elements[it].bounds }
    }

    // A line whose centre lies in an element with text, or that covers more than half of its area with it, is dropped.
    private fun overlapsText(line: OcrLine, snapshot: ScreenSnapshot): Boolean = snapshot.elements.any { element ->
        if (element.label.isNullOrBlank()) return@any false
        val overlap = element.bounds.intersect(line.bounds) ?: return@any false
        val centre = IntRect(line.bounds.centerX, line.bounds.centerY, line.bounds.centerX + 1, line.bounds.centerY + 1)
        element.bounds.contains(centre) || overlap.width.toLong() * overlap.height * 2 > line.bounds.width.toLong() * line.bounds.height
    }

    // Password fields, data-sensitive elements, every line of the screen's observation whose text the redactor changed
    // (merged rows and the lines that the cap left out included), and OCR boxes whose text it changes with the other boxes as neighbours, whatever their
    // score (D20; review B I12, B2-M8).
    private suspend fun masks(snapshot: ScreenSnapshot, lines: List<OcrLine>): List<IntRect> {
        val secret = snapshot.elements.filter { it.password || it.dataSensitive }.map { it.bounds }
        val kept = lines.filter { it.score >= MIN_SCORE && !overlapsText(it, snapshot) }
        val redacted = run.table(snapshot, kept).redactedBounds
        val texts = lines.map { it.text }
        val ocr = lines.filterIndexed { index, line ->
            run.redactor.redact(line.text, texts.filterIndexed { other, _ -> other != index }) != line.text
        }.map { it.bounds }
        return secret + redacted + ocr
    }

    private enum class Approval { ALWAYS, ONCE, NO }

    // D20: only "always" and "always for this app" approve for good, and only the yes words send once; an answer with a
    // word of refusal is a no ("No, always ask me first.").
    private fun approval(answer: Answer): Approval {
        val text = when (answer) {
            is Answer.Text -> Normalizer.normalize(answer.text)
            Answer.Yes -> "yes"
            else -> return Approval.NO
        }
        return when {
            Normalizer.words(text).any { it in REFUSALS } -> Approval.NO
            text in ALWAYS -> Approval.ALWAYS
            text in YES -> Approval.ONCE
            else -> Approval.NO
        }
    }

    private fun badges(): List<Badge> = run.observation.table.entries.map { Badge(it.number, it.bounds) }

    // The numbers as the card's choices when there are at most OwnerPort.MAX_CHOICES of them and they run from 1, so that
    // choice number i answers the number i (plan 7F); otherwise none, and the owner gives the number.
    private fun choices(): List<String> {
        val numbers = numbered() ?: return emptyList()
        return if (numbers.size > OwnerPort.MAX_CHOICES) emptyList() else numbers.map(Int::toString)
    }

    // The highest number, which the card's keypad keeps to (plan 7F), when the numbers run from 1; otherwise 0, no range.
    private fun highest(): Int = numbered()?.size ?: 0

    private fun numbered(): List<Int>? = run.observation.table.entries.map { it.number }.takeIf { it == (1..it.size).toList() }

    override fun toString(): String = "LookCloser(screens=${calls.size})"

    companion object {
        const val MIN_SCORE = 0.8
        const val WHICH = "Which number should I use?"
        const val SECURE = "This screen cannot be captured (the app blocks screenshots)."
        const val NO_SCREENSHOT = "the screenshot failed"
        const val CHANGED = "The screen changed while the owner answered, so Kestrel took no screenshot."
        const val MOVED = "The screen moved while Kestrel captured it, so Kestrel took no screenshot."
        private const val CAPTURES = 2

        // Kestrel learns of a move about 150 ms after it: an app's content-change events come at most every 100 ms, and
        // Android delivers them 50 ms later (the service's notificationTimeout). Once the event is in, the snapshot cache
        // itself waits for a quiet screen before it builds the next tree.
        private const val MOVE_SEEN_MILLIS = 250L

        private const val LARGE_SHARE = 16
        private val YES = setOf("yes", "yes send it", "ok", "yes please", "sure")
        private val ALWAYS = setOf("always", "always for this app", "yes always")
        private val REFUSALS = setOf("no", "not", "never", "dont", "ne")
    }
}
