package dev.kestrel.agent.loop

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.agent.observe.TableEntry
import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.tools.ActionMapper
import dev.kestrel.agent.tools.AgentCall
import dev.kestrel.agent.tools.Browser
import dev.kestrel.commands.text.NamePick
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Pick
import dev.kestrel.safety.ActionKind
import dev.kestrel.safety.Category
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.ConfirmationBuilder
import dev.kestrel.safety.Decision
import dev.kestrel.safety.LinkCheck
import dev.kestrel.safety.LinkVerdict
import dev.kestrel.safety.Placeholders
import dev.kestrel.safety.Proposal
import dev.kestrel.safety.SafetyCheck
import dev.kestrel.safety.SendCheck
import dev.kestrel.safety.SendCheckResult
import dev.kestrel.safety.Vault
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTree
import kotlinx.coroutines.delay
import java.net.URI

/**
 * The screen tools (design D3, D10, D11, D13 and D14; D32 items 12, 27 and 29): every action on an element reads a fresh
 * snapshot, checks the element against what the observation showed, passes the safety check, and after the owner's yes
 * reads the screen again and presses only when the confirmation and test mode's check still hold.
 */
internal class ScreenSteps(private val run: TaskRun) {
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

    // After the yes (D14): the card has gone; the screen is read again and the confirmation built again on it.
    private suspend fun afterYes(call: AgentCall, entry: TableEntry?, proposal: Proposal, yes: Confirmation, category: Category, covered: Boolean): Done {
        val fresh = run.fresh()
        if (!SafetyCheck.testModeAllows(proposal, fresh, run.safety, run.settings.safety, run.settings.environment)) {
            throw Ended(EndReason.TEST_MODE, detail = TaskRun.TEST_MODE_BLOCK)
        }
        val again = ConfirmationBuilder.build(proposal, fresh, category, run.safety)
        if (again !is Decision.Confirm || !again.confirmation.sameAs(yes) || (entry != null && !entry.ocr && !fits(entry, fresh))) {
            run.forgetConfirmedFlow()
            return Done(call.tool, Outcome.CheckFailed(CHANGED_AFTER), entry, after = fresh)
        }
        sendCheck(proposal, fresh, call.tool, entry)?.let { return it }
        val flow = run.safety.shareFlow
        val done = perform(call, entry, proposal, fresh, category, confirmed = true)
        // A press that may not have worked is not a send (D32 item 29): the report never claims it, and the next press of
        // the share flow needs a new yes.
        if (done.outcome == Outcome.Uncertain) {
            run.unsure(yes, share = flow != null)
            run.forgetConfirmedFlow()
        } else if (done.outcome !is Outcome.Failed) {
            val items = flow?.count?.let { it.photos + it.videos } ?: 0
            // A share that the report already holds is not counted again when a doubted tap of it was confirmed anew.
            val counted = flow != null && run.countedShare == flow.serial to yes.recipients
            run.sent(yes, share = flow != null, items = items, covered = covered || counted)
            if (flow != null && yes.category == Category.SENDING) run.countedShare = flow.serial to yes.recipients
        }
        return done
    }

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
        val after = run.fresh()
        run.safety = SafetyCheck.afterAction(proposal.copy(text = revealed ?: proposal.text), before, after, run.safety)
        val key = entry?.key ?: (action as? ScreenAction.Scroll)?.key ?: (action as? ScreenAction.Swipe)?.key
        val changed = Changes.outcome(before, after, key, run.settings::closed)
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
        return open(call, ScreenAction.OpenApp(app.packageName), app.packageName, app.label)
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
        val done = open(call, ScreenAction.OpenUri(link, browserPackage, browsable = true), target, null, linkTarget = target)
        if (browser != Browser.FIREFOX || browserPackage == null || done.after == null) return done
        return firefox(call, done, link, browserPackage)
    }

    // Firefox's web area can stay empty (r57's bug 2023734): wait for it, send the intent once more, then say so (D11).
    private suspend fun firefox(call: AgentCall, done: Done, link: String, browserPackage: String): Done {
        for (attempt in 0..1) {
            repeat(WAIT_POLLS) {
                val now = run.fresh()
                if (webAreaFilled(now, browserPackage)) return Done(call.tool, done.outcome, after = now, detail = done.detail)
                delay(POLL_MILLIS)
            }
            if (attempt == 0) run.ports.screen.act(ScreenAction.OpenUri(link, browserPackage, browsable = true))
        }
        return Done(call.tool, Outcome.Failed(EMPTY_PAGE), after = run.fresh())
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
        Done(tool, text = "Result: $PAUSED", summary = "Step ${run.replies}: the task was paused; nothing was done.", last = "paused; nothing was done", target = entry, after = run.fresh(), progress = false)

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
    private fun fromOwner(link: String): Boolean {
        val host = try {
            URI(link).host
        } catch (e: Exception) {
            null
        } ?: link
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
        const val UNCERTAIN = "The tap may not have worked; check the screen before trying again."
        const val PAUSED = "The task was paused before this action, so nothing was done."
        const val EMPTY_PAGE = "the page shows no content to Kestrel"
        const val ACTION_FAILED = "the action failed"
        const val NOTHING_TO_ACT_ON = "there is nothing to act on"
        const val FRONT_WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 500L
        private const val WAIT_POLLS = 10
        private const val VIEW = "android.intent.action.VIEW"
        private const val WEB_VIEW = "android.webkit.WebView"
        private val WATCHED = setOf(Category.SENDING, Category.CALLS, Category.DELETING)
    }
}
