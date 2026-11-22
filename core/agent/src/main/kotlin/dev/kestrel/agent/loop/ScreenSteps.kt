package dev.kestrel.agent.loop

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.agent.observe.TableEntry
import dev.kestrel.agent.port.Capture
import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.tools.ActionMapper
import dev.kestrel.agent.tools.AgentCall
import dev.kestrel.agent.tools.Browser
import dev.kestrel.commands.text.NamePick
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Pick
import dev.kestrel.safety.ActionKind
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.Category
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.Decision
import dev.kestrel.safety.HandOver
import dev.kestrel.safety.HandOverKind
import dev.kestrel.safety.LinkCheck
import dev.kestrel.safety.LinkVerdict
import dev.kestrel.safety.Placeholders
import dev.kestrel.safety.Proposal
import dev.kestrel.safety.SafetyCheck
import dev.kestrel.safety.SendCheck
import dev.kestrel.safety.SendCheckResult
import dev.kestrel.safety.ShareFlow
import dev.kestrel.safety.Vault
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTree
import kotlinx.coroutines.delay
import java.net.URI
import kotlin.math.abs

/**
 * The screen tools (design D3, D10, D11, D13 and D14; D32 items 12, 27 and 29): every action on an element reads a fresh
 * snapshot, checks the element against what the observation showed, passes the safety check, and after the owner's yes
 * reads the screen again and presses only when the whole safety check, run again on it, asks the same question.
 */
internal class ScreenSteps(private val run: TaskRun) {
    // Whether the last open() sent its link, which the Firefox retry needs (a pause, a refusal or a hand-over sends none).
    private var sentLink = false

    suspend fun element(call: AgentCall): Done {
        val table = run.observation.table
        val entry = when (call) {
            is AgentCall.Tap -> table.entry(call.target)
            is AgentCall.LongPress -> table.entry(call.target)
            is AgentCall.TypeText -> table.entry(call.target)
            is AgentCall.Scroll -> call.target?.let(table::entry)
            is AgentCall.Swipe -> call.target?.let(table::entry)
            else -> null
        }
        while (true) {
            if (run.gate()) return paused(call.tool, entry)
            val before = run.fresh()
            if (entry != null && !entry.ocr && !fits(entry, before)) return Done(call.tool, Outcome.Failed(CHANGED_BEFORE), entry, after = before)
            if (call is AgentCall.Swipe) {
                ActionMapper.swipeRefusal(call, table, before)?.let { return Done(call.tool, Outcome.Refused(it), entry, after = before) }
            }
            val proposal = ActionMapper.proposal(call, table, run.observation.appLabel)
            sendCheck(proposal, before, call.tool, entry)?.let { return it }
            when (val clearance = run.decide(proposal, before, call.tool, entry)) {
                Clearance.Again -> continue
                is Clearance.Stop -> return clearance.done
                is Clearance.Go -> {
                    val yes = clearance.confirmation ?: return perform(call, entry, proposal, before, clearance.category, confirmed = false)
                    return afterYes(call, entry, proposal, yes, clearance.category, clearance.covered)
                }
            }
        }
    }

    // After the yes (D14): the card has gone; the screen is read again, and the whole safety check runs on it again. Only
    // the same question on the fresh screen (the same recipients, app, content and target, also the element under a
    // point) lets the press run; a block that only test mode made ends the task. A tap on an OCR line also needs its row
    // to be the confirmed one ([pointChecked]).
    private suspend fun afterYes(call: AgentCall, entry: TableEntry?, proposal: Proposal, yes: Confirmation, category: Category, covered: Boolean): Done {
        var fresh = run.fresh()
        if (!confirmedOn(fresh, proposal, yes, entry)) return changedAfter(call, entry, fresh)
        if (entry != null && entry.ocr) {
            when (val point = pointChecked(entry, proposal, yes, fresh)) {
                is PointCheck.Press -> fresh = point.screen
                is PointCheck.Changed -> return changedAfter(call, entry, point.screen)
                is PointCheck.Owner -> {
                    run.forgetConfirmedFlow()
                    return run.handOver(point.handOver, call.tool)
                }
            }
        }
        sendCheck(proposal, fresh, call.tool, entry)?.let { return it }
        val flow = run.safety.shareFlow
        val sharing = flow?.takeIf { yes.category == Category.SENDING }
        // The share counts as tried from the moment the tap worked, so that a cancel while Kestrel waits for its chat still
        // reports it, never "I didn't change anything" (D24).
        val done = perform(call, entry, proposal, fresh, category, confirmed = true, shareFlow = sharing) {
            sharing?.let { run.shareTapped(it, yes, ended = false) }
        }
        when {
            // A press that may not have worked is not a send (D32 item 29): the report never claims it, and the next press
            // of the share flow needs a new yes.
            done.outcome == Outcome.Uncertain -> {
                run.unsure(yes, share = flow != null)
                run.forgetConfirmedFlow()
            }
            done.outcome is Outcome.Failed -> Unit
            // A tap that opened an e-mail's compose screen (a share sheet's mail app) sent nothing yet: the e-mail's own
            // Send is asked and counted (D14, D24).
            yes.category == Category.SENDING && !SafetyCheck.showsEmailCompose(fresh) && done.after?.let(SafetyCheck::showsEmailCompose) == true ->
                sharing?.let(run::forgetPendingShare)
            // A share is sent by the tap after which its flow ends, not by a tap that only picks a chat (D14, D24).
            flow != null && yes.category == Category.SENDING -> run.shareTapped(flow, yes, ended = run.safety.shareFlow?.serial != flow.serial)
            else -> run.sent(yes, share = false, items = 0, covered = covered)
        }
        return done
    }

    // The whole safety check asks the same question on [screen], and a tree element still shows what the observation showed.
    private fun confirmedOn(screen: ScreenSnapshot, proposal: Proposal, yes: Confirmation, entry: TableEntry?): Boolean {
        val again = SafetyCheck.assess(proposal, screen, run.safety, run.settings.safety, run.settings.environment)
        val decision = again.decision
        if (decision is Decision.Block && again.beforeTestMode !is Decision.Block) throw Ended(EndReason.TEST_MODE, detail = decision.reason)
        return decision is Decision.Confirm && decision.confirmation.sameAs(yes) && (entry == null || entry.ocr || fits(entry, screen))
    }

    private fun changedAfter(call: AgentCall, entry: TableEntry?, screen: ScreenSnapshot): Done {
        run.forgetConfirmedFlow()
        return Done(call.tool, Outcome.CheckFailed(CHANGED_AFTER), entry, after = screen)
    }

    private sealed interface PointCheck {
        class Press(val screen: ScreenSnapshot) : PointCheck
        class Changed(val screen: ScreenSnapshot) : PointCheck
        class Owner(val handOver: HandOver) : PointCheck
    }

    // A point has no key of its own, and list rows share their view IDs, so a list that moved under the point can keep
    // the key and bounds of the element there, also of a row's own "Delete" control (D14, D20). So the press needs its whole
    // row: the band of the tapped line, as wide as the point's window and one line's height above and below it. When the
    // line lies in a scrollable list, the band keeps only the list's part and the lines that move with its rows, because a
    // line that stays in place, such as a floating button's, can neither tell rows apart nor show that they moved. Rows that
    // look alike, a front app that is agent-free and a Play services window hand the press to the owner, because no
    // screenshot may or can tell the rows apart (D15, D16, D20). Otherwise OCR reads the band again, and every line that
    // the observation showed in it must still be there, with the same text at the same place; then the screen is read
    // once more, and the window and the element under the point must be as before, so that nothing came over the point
    // during the OCR. A capture or an OCR pass that fails counts as a change.
    private suspend fun pointChecked(entry: TableEntry, proposal: Proposal, yes: Confirmation, before: ScreenSnapshot): PointCheck {
        val x = entry.bounds.centerX
        val y = entry.bounds.centerY
        val window = windowAt(before, x, y) ?: return PointCheck.Changed(before)
        if (!run.capturable(before)) {
            val playServices = before.windows.any { it.packageName == AgentFreeApps.PLAY_SERVICES }
            return PointCheck.Owner(if (playServices) HandOver.of(HandOverKind.PLAY_SERVICES) else HandOver(HandOverKind.RECIPIENT, PRESS_YOURSELF))
        }
        val margin = entry.bounds.height
        val list = rowsList(entry)
        val band = IntRect(window.bounds.left, entry.bounds.top - margin, window.bounds.right, entry.bounds.bottom + margin)
            .intersect(window.bounds)?.let { if (list == null) it else it.intersect(run.snapshot.elements[list].bounds) }
            ?: return PointCheck.Changed(before)
        val lines = run.observation.table.entries.filter {
            it.windowId == entry.windowId && !it.label.isNullOrBlank() && holdsCentre(band, it.bounds) && (list == null || movesWith(it, list))
        }
        if (looksAlike(entry, lines)) return PointCheck.Owner(HandOver(HandOverKind.RECIPIENT, PRESS_YOURSELF))
        if (!lines.filter { !it.ocr }.all { stillShown(it, before) }) return PointCheck.Changed(before)
        val capture = run.ports.eyes.capture() as? Capture.Captured ?: return PointCheck.Changed(before)
        val exclude = before.elements.filter { it.password || it.dataSensitive }.map { it.bounds }
        val read = capture.image.use { run.ports.eyes.read(capture, listOf(band), exclude) } ?: return PointCheck.Changed(before)
        val same = lines.filter { it.ocr }.all { line -> read.any { it.text.trim() == line.label?.trim() && near(it.bounds, line.bounds, 0, 0) } }
        val now = run.fresh()
        if (!same) return PointCheck.Changed(now)
        val sameWindow = windowAt(now, x, y)?.let { it.id == window.id && it.bounds == window.bounds } == true
        if (!sameWindow || !confirmedOn(now, proposal, yes, entry) || !lines.filter { !it.ocr }.all { stillShown(it, now) }) return PointCheck.Changed(now)
        return PointCheck.Press(now)
    }

    // Another line with the tapped line's text whose row, moved onto the tapped one, shows every line of the band with the
    // same text at the same place, such as a "Delete" in every row of identical rows: no OCR pass can tell them apart.
    private fun looksAlike(entry: TableEntry, lines: List<TableEntry>): Boolean {
        val entries = run.observation.table.entries
        return entries.any { other ->
            val dx = other.bounds.centerX - entry.bounds.centerX
            val dy = other.bounds.centerY - entry.bounds.centerY
            other !== entry && other.windowId == entry.windowId && other.label?.trim() == entry.label?.trim() && !near(other.bounds, entry.bounds, 0, 0) &&
                lines.all { line -> entries.any { it.label?.trim() == line.label?.trim() && near(it.bounds, line.bounds, dx, dy) } }
        }
    }

    // A tree line of the band: the same element at the same place.
    private fun stillShown(line: TableEntry, screen: ScreenSnapshot): Boolean {
        val element = line.key?.let(screen::element) ?: return false
        return fits(line, screen) && near(element.bounds, line.bounds, 0, 0)
    }

    // The index, in the observation's snapshot, of the list whose rows hold the tapped line: of the scrollable elements of
    // the point's window that hold the point, the innermost vertical list, else the innermost one. Null when there is none
    // or when the line does not move with its rows, such as a floating button's text over the list.
    private fun rowsList(entry: TableEntry): Int? {
        val elements = run.snapshot.elements
        val point = IntRect(entry.bounds.centerX, entry.bounds.centerY, entry.bounds.centerX + 1, entry.bounds.centerY + 1)
        val lists = elements.indices.filter { elements[it].scrollable && elements[it].windowId == entry.windowId && holdsCentre(elements[it].bounds, point) }
        val list = lists.filter { ScreenTree.isVerticalList(elements[it]) }.ifEmpty { lists }.minByOrNull { area(elements[it].bounds) } ?: return null
        return list.takeIf { movesWith(entry, it) }
    }

    // Whether [line] moves with the rows of [list]. A tree line must be the list or lie inside it in the tree. An OCR line
    // must lie inside the list's bounds, and the smallest element that holds it must not be one outside the list, such as
    // a floating button or a toolbar drawn over it.
    private fun movesWith(line: TableEntry, list: Int): Boolean {
        val snapshot = run.snapshot
        val elements = snapshot.elements
        fun inList(index: Int) = index == list || list in ScreenTree.ancestors(snapshot, index)
        if (!line.ocr) {
            val index = line.key?.let { key -> elements.indexOfFirst { it.key == key } }?.takeIf { it >= 0 } ?: return true
            return inList(index)
        }
        if (!holdsCentre(elements[list].bounds, line.bounds)) return false
        val above = ScreenTree.ancestors(snapshot, list).toSet()
        val (inside, outside) = elements.indices
            .filter { elements[it].windowId == line.windowId && it !in above && holdsCentre(elements[it].bounds, line.bounds) }
            .partition(::inList)
        val own = inside.minOfOrNull { area(elements[it].bounds) } ?: return false
        return outside.none { area(elements[it].bounds) < own }
    }

    private fun area(bounds: IntRect): Long = bounds.width.toLong() * bounds.height

    // [bounds] lies at [shown] moved by ([dx], [dy]), within half of [shown]'s height.
    private fun near(bounds: IntRect, shown: IntRect, dx: Int, dy: Int): Boolean {
        val slack = maxOf(1, shown.height / 2)
        return abs(bounds.centerX - shown.centerX - dx) <= slack && abs(bounds.centerY - shown.centerY - dy) <= slack
    }

    // The highest window that holds the point, as Android delivers a tap there.
    private fun windowAt(screen: ScreenSnapshot, x: Int, y: Int) =
        screen.windows.filter { holdsCentre(it.bounds, IntRect(x, y, x + 1, y + 1)) }.maxByOrNull { it.layer }

    private fun holdsCentre(area: IntRect, bounds: IntRect): Boolean =
        bounds.centerX >= area.left && bounds.centerX < area.right && bounds.centerY >= area.top && bounds.centerY < area.bottom

    // The check before a send, save, submit or confirm (D14; r61): the typed values must still be in their fields.
    private fun sendCheck(proposal: Proposal, screen: ScreenSnapshot, tool: String, entry: TableEntry?): Done? {
        if (!SendCheck.applies(proposal, screen)) return null
        val result = SendCheck.check(proposal, screen, run.safety) as? SendCheckResult.Failed ?: return null
        val what = if (result.wrongApp) {
            "the share is no longer in the app that it opened"
        } else {
            "the message field shows ${ScreenText.quote(run.redactor.redact(result.fieldText), ScreenText.VALUE_LIMIT)}, not the text that I typed"
        }
        return Done(tool, Outcome.CheckFailed(what), entry, after = screen)
    }

    private suspend fun perform(
        call: AgentCall,
        entry: TableEntry?,
        proposal: Proposal,
        before: ScreenSnapshot,
        category: Category,
        confirmed: Boolean,
        shareFlow: ShareFlow? = null,
        pressed: () -> Unit = {},
    ): Done {
        val revealed = (call as? AgentCall.TypeText)?.let { typed ->
            // A value of the vault that Kestrel types is named by its kind in every later question (D14, D17).
            Vault.PLACEHOLDER.findAll(typed.text).map { it.value }.distinct().forEach { placeholder ->
                run.vault.valueOf(placeholder)?.let { run.safety = run.safety.withSecret(it, Placeholders.forConfirmation(placeholder)) }
            }
            run.vault.reveal(typed.text)
        }
        val press = ActionMapper.press(category, confirmed)
        val action = ActionMapper.screenAction(call, run.observation.table, revealed, press)
            ?: return Done(call.tool, Outcome.Failed(NOTHING_TO_ACT_ON), entry, after = before)
        val outcome = run.ports.screen.act(action)
        if (!confirmed && category in WATCHED) run.unconfirmed++
        if (outcome.done) pressed()
        val key = entry?.key ?: (action as? ScreenAction.Scroll)?.key ?: (action as? ScreenAction.Swipe)?.key
        val acted = proposal.copy(text = revealed ?: proposal.text)
        var after = run.fresh()
        var changed = Changes.outcome(before, after, key, run.settings::closed)
        var safety = SafetyCheck.afterAction(acted, before, after, run.safety)
        // A share target can show the chat that a press sent to only after the press has settled, such as a chat that opens
        // in a new activity on a slow phone, and the screens in between show neither (D14, D24). So a press of share flow
        // [shareFlow] is judged on the first screen that ends the flow or changes within the same screen, as a row that only
        // picks a chat does at once; after FRONT_WAIT_MILLIS, on the last screen.
        if (shareFlow != null && outcome.done) {
            var waited = 0L
            while (safety.shareFlow?.serial == shareFlow.serial && (changed == Outcome.NothingChanged || changed is Outcome.AnotherScreen) &&
                waited < FRONT_WAIT_MILLIS
            ) {
                delay(CHANGE_POLL_MILLIS)
                waited += CHANGE_POLL_MILLIS
                after = run.fresh()
                changed = Changes.outcome(before, after, key, run.settings::closed)
                safety = SafetyCheck.afterAction(acted, before, after, run.safety)
            }
        }
        run.safety = safety
        val result = when {
            !outcome.done -> Outcome.Failed(outcome.reason ?: ACTION_FAILED)
            outcome.uncertain && changed == Outcome.NothingChanged -> Outcome.Uncertain
            else -> changed
        }
        val extra = if (result == Outcome.Uncertain) Redacted.kestrel(UNCERTAIN) else null
        return Done(call.tool, result, entry, detail = direction(call), after = after, extra = extra)
    }

    private fun direction(call: AgentCall): String? = when (call) {
        is AgentCall.Scroll -> call.direction.name.lowercase()
        is AgentCall.Swipe -> call.direction.name.lowercase()
        else -> null
    }

    suspend fun openApp(call: AgentCall.OpenApp): Done {
        val apps = run.ports.apps.launcherApps()
        val app = when (val pick = NamePick.pick(call.name, apps, { it.label }, { false })) {
            Pick.None -> return Done(call.tool, Outcome.Failed("no app named ${quoted(call.name)}"))
            is Pick.Several -> return Done(call.tool, Outcome.Failed("several apps match: " + pick.items.take(NamePick.MAX_CHOICES).joinToString(", ") { quoted(it.label) }))
            is Pick.One -> pick.item
        }
        if (run.settings.closed(app.packageName)) return Done(call.tool, Outcome.Refused("${quoted(app.label)} is agent-free"))
        val done = open(call, ScreenAction.OpenApp(app.packageName), app.packageName, app.label)
        // The notes come only with an app that is in front now: not after a failure, a refusal, a pause or a hand-over,
        // so that a task never sees, and never strikes, the notes of an app that did not open (D22).
        val opened = done.executed && done.text == null && done.outcome !is Outcome.Failed && done.outcome !is Outcome.Refused &&
            done.after?.let { ScreenTree.frontWindow(it)?.packageName } == app.packageName
        if (!opened) return done
        val notes = run.recalled(null) { run.memory.notesFor(app.packageName) } ?: return done
        return Done(done.tool, done.outcome, detail = done.detail, after = done.after, extra = Redacted.kestrel(notes), text = done.text, summary = done.summary, last = done.last, progress = done.progress)
    }

    suspend fun openLink(call: AgentCall): Done {
        val link = when (call) {
            is AgentCall.OpenUrl -> call.url
            is AgentCall.OpenDeepLink -> call.uri
            else -> throw IllegalArgumentException("Not a link")
        }
        val kind = if (call is AgentCall.OpenUrl) ActionKind.OPEN_URL else ActionKind.OPEN_DEEP_LINK
        val browser = (call as? AgentCall.OpenUrl)?.browser ?: Browser.DEFAULT
        val installed = run.ports.apps.launcherApps().map { it.packageName }.toSet()
        val browserPackage = browser.packages.firstOrNull { it in installed }
        if (browser != Browser.DEFAULT && browserPackage == null) return Done(call.tool, Outcome.Failed(if (browser == Browser.FIREFOX) "Firefox is not installed" else "Chrome is not installed"))
        val target = run.ports.screen.resolve(IntentSpec(VIEW, link, browserPackage, browsable = true))
        val verdict = LinkCheck.check(link, kind, fromOwner(link), target, run.settings::closed, run.settings.emergency)
        if (verdict is LinkVerdict.Refused) return Done(call.tool, Outcome.Refused(verdict.reason))
        sentLink = false
        val done = open(call, ScreenAction.OpenUri(link, browserPackage, browsable = true), target, null, linkTarget = target)
        // The retry is Firefox's, also when Firefox is the default browser that the link resolved to (r57); only after the
        // first intent was sent, never after a pause, a refusal or a hand-over, so the link never opens unchecked (D2, D12).
        if (!sentLink || target == null || target !in Browser.FIREFOX.packages || done.after == null) return done
        return firefox(call, done, link, browserPackage, target)
    }

    // Firefox's web area can stay empty (r57's bug 2023734): wait for it, send the intent once more, then say so (D11).
    private suspend fun firefox(call: AgentCall, done: Done, link: String, browserPackage: String?, firefox: String): Done {
        for (attempt in 0..1) {
            repeat(WAIT_POLLS) {
                val now = run.fresh()
                if (webAreaFilled(now, firefox)) return Done(call.tool, done.outcome, after = now, detail = done.detail)
                delay(POLL_MILLIS)
            }
            if (attempt == 0) {
                // The second intent is an action, so it waits at the pause gate; after a pause it is not sent, because the
                // owner may have changed the screen (D2).
                if (run.gate()) break
                run.ports.screen.act(ScreenAction.OpenUri(link, browserPackage, browsable = true))
            }
        }
        val now = run.fresh()
        if (webAreaFilled(now, firefox)) return Done(call.tool, done.outcome, after = now, detail = done.detail)
        return Done(call.tool, Outcome.Failed(EMPTY_PAGE), after = now)
    }

    private fun webAreaFilled(snapshot: ScreenSnapshot, browserPackage: String): Boolean {
        val areas = snapshot.elements.indices.filter {
            snapshot.elements[it].className == WEB_VIEW && snapshot.elements[it].packageName == browserPackage
        }
        return areas.any { area -> snapshot.elements.any { it.parent == area } }
    }

    suspend fun openSettings(call: AgentCall.OpenSettings): Done {
        for (action in call.page.actions) {
            val spec = IntentSpec(action)
            val target = run.ports.screen.resolve(spec) ?: continue
            // The result names the page that opened, which can be a fallback of the page asked for (D11).
            val page = if (action == call.page.actions.first()) call.page.wire else pageName(action)
            return open(call, ScreenAction.OpenIntent(spec), target, page)
        }
        return Done(call.tool, Outcome.Failed("this phone has no such settings page"))
    }

    // Opens something, then waits up to 5 s until its app is in front (D11; review A M15, A2-N7).
    private suspend fun open(call: AgentCall, action: ScreenAction, target: String?, detail: String?, linkTarget: String? = null): Done {
        while (true) {
            if (run.gate()) return paused(call.tool, null)
            val before = run.fresh()
            // A link's question names the app that opens it by its launcher label (D32 item 12).
            val proposal = ActionMapper.proposal(
                call, run.observation.table, run.observation.appLabel,
                receivingAppLabel = linkTarget?.let { run.label(it) },
                linkFromOwner = linkOf(call)?.let(::fromOwner) ?: false,
                linkTargetPackage = linkTarget,
            )
            when (val clearance = run.decide(proposal, before, call.tool, null)) {
                Clearance.Again -> continue
                is Clearance.Stop -> return clearance.done
                is Clearance.Go -> {
                    val outcome = run.ports.screen.act(action)
                    if (!outcome.done) return Done(call.tool, Outcome.Failed(outcome.reason ?: ACTION_FAILED), after = run.fresh())
                    if (action is ScreenAction.OpenUri) sentLink = true
                    run.ports.screen.waitForFront(target, FRONT_WAIT_MILLIS, before = ScreenTree.frontWindow(before)?.packageName)
                    val after = run.fresh()
                    run.safety = SafetyCheck.afterAction(proposal, before, after, run.safety)
                    return Done(call.tool, Changes.outcome(before, after, null, run.settings::closed), detail = detail, after = after)
                }
            }
        }
    }

    suspend fun wait(call: AgentCall.Wait): Done {
        val before = run.fresh()
        var now = before
        for (poll in 1..call.seconds * 2) {
            delay(POLL_MILLIS)
            now = run.fresh()
            if (Changes.outcome(before, now, null, run.settings::closed) != Outcome.NothingChanged) break
        }
        return Done(call.tool, Changes.outcome(before, now, null, run.settings::closed), after = now)
    }

    private suspend fun paused(tool: String, entry: TableEntry?): Done =
        Done(tool, text = "Result: $PAUSED", summary = "Step ${run.replies}: the task was paused; nothing was done.", last = "paused; nothing was done", target = entry, after = run.fresh(), progress = false, executed = false)

    // The element still shows what the observation showed (D32 item 27).
    private fun fits(entry: TableEntry, screen: ScreenSnapshot): Boolean {
        val element = entry.key?.let(screen::element) ?: return false
        return element.label == entry.label && element.className == entry.className && element.packageName == entry.packageName &&
            element.editable == entry.editable && element.password == entry.password && element.checkable == entry.checkable
    }

    private fun linkOf(call: AgentCall): String? = when (call) {
        is AgentCall.OpenUrl -> call.url
        is AgentCall.OpenDeepLink -> call.uri
        else -> null
    }

    // A link came from the owner when its host (or for a deep link, the whole link) is in his request (D32 item 12): its
    // words as a whole run of the request's words, or the host as one spoken word ("wikipedia.org" said "wikipedia org").
    // Only an https link has a host that names a place: in `whatsapp://send?…`, `viber://forward?…` or `tg://share?…` the
    // "host" is an action word that most messaging requests hold (D12).
    private fun fromOwner(link: String): Boolean {
        val uri = try {
            URI(link)
        } catch (e: Exception) {
            null
        }
        val host = uri?.host?.takeIf { uri.scheme.equals(HTTPS, ignoreCase = true) } ?: link
        val words = Normalizer.words(host)
        val request = Normalizer.words(run.task.text)
        if (words.isEmpty()) return false
        val joined = words.joinToString("")
        return request.windowed(words.size).any { it == words } || request.any { it == joined }
    }

    // "android.settings.WIRELESS_SETTINGS" is the page "wireless".
    private fun pageName(action: String): String =
        action.substringAfterLast('.').removeSuffix("_SETTINGS").lowercase().replace('_', ' ')

    private fun quoted(text: String): String = ScreenText.quote(run.redactor.redact(text), ScreenText.LABEL_LIMIT)

    override fun toString(): String = "ScreenSteps"

    companion object {
        const val CHANGED_BEFORE = "the screen changed before the action; nothing was done"
        const val CHANGED_AFTER = "the screen changed after the confirmation; nothing was sent"
        const val PRESS_YOURSELF = "I can't check which row this press lands on. Please check it and press it yourself, then say 'Kestrel, continue'."
        const val UNCERTAIN = "The tap may not have worked; check the screen before trying again."
        const val PAUSED = "The task was paused before this action, so nothing was done."
        const val EMPTY_PAGE = "the page shows no content to Kestrel"
        const val ACTION_FAILED = "the action failed"
        const val NOTHING_TO_ACT_ON = "there is nothing to act on"
        const val FRONT_WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 500L
        private const val CHANGE_POLL_MILLIS = 100L
        private const val WAIT_POLLS = 10
        private const val VIEW = "android.intent.action.VIEW"
        private const val HTTPS = "https"
        private const val WEB_VIEW = "android.webkit.WebView"
        private val WATCHED = setOf(Category.SENDING, Category.CALLS, Category.DELETING)
    }
}
