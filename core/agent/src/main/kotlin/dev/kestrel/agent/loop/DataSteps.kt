package dev.kestrel.agent.loop

import dev.kestrel.agent.DataResultText
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.tools.ActionMapper
import dev.kestrel.agent.tools.AgentCall
import dev.kestrel.agent.tools.SharePackages
import dev.kestrel.agent.tools.Tools
import dev.kestrel.commands.text.NamePick
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Pick
import dev.kestrel.safety.Category
import dev.kestrel.safety.HandOver
import dev.kestrel.safety.HandOverKind
import dev.kestrel.safety.SafetyCheck
import dev.kestrel.safety.ShareCount
import dev.kestrel.screen.model.ScreenTree
import java.util.Locale

/**
 * The data tools (design D12): each runs through `DataPort` after the safety check; a missing permission is asked at
 * first use with Kestrel's reason and the tool runs again (spec 11.5, 13.6); notification access is a hand-over of kind
 * `ACCESS` (D9); a tool that opens an app's screen waits until that app is in front (D11). The result's values are
 * redacted with their neighbours and quoted by `DataResultText` (review A3-I1).
 */
internal class DataSteps(private val run: TaskRun) {
    // find_media's items: true for a video. 5B writes each item's fixed text as "<id> · photo · …" or "<id> · video · …".
    private val videos = mutableMapOf<String, Boolean>()

    // find_media's note on partial photo access, which the owner hears once before a share uses the items (D12).
    private var mediaNote: String? = null

    // The newest find_contact's contacts, by their number there, with Kestrel's handles (design D19 of sub-project 6).
    private var found: List<FoundContact> = emptyList()

    private class FoundContact(val ref: String?, val name: String, val number: String?) {
        override fun toString(): String = "FoundContact(ref=${ref != null}, name=${name.length} chars)"
    }

    // A data tool's app screen that opened: [front] is the app to wait for, when it comes to the front, and [done] the
    // tool's fixed sentence.
    private class Opened(val front: String?, val done: String)

    suspend fun run(asked: AgentCall): Done {
        val call = resolved(asked)
        var sharePackage: String? = null
        var receivingLabel: String? = null
        if (call is AgentCall.ShareMedia) {
            sharePackage = sharePackage(call) ?: return unknownApp(call)
            if (run.settings.closed(sharePackage)) return Done(call.tool, Outcome.Refused("${quoted(run.label(sharePackage) ?: call.app)} is agent-free"))
            receivingLabel = run.label(sharePackage)
            mediaNote?.let { note ->
                mediaNote = null
                run.gate()
                run.limits.waiting { run.ports.owner.say(note) }
            }
            if (call.items.size > MAX_SILENT_SHARE && !run.askYesNo("Share ${call.items.size} items?")) throw Ended(EndReason.DECLINED)
        }
        // delete_contact names a contact of the newest find_contact by its number there; its ID stays on the phone.
        val contact = (call as? AgentCall.DeleteContact)?.let { delete ->
            if (found.isEmpty()) return Done(call.tool, Outcome.Failed(ActionMapper.FIND_FIRST))
            found.getOrNull(delete.contact - 1) ?: return Done(call.tool, Outcome.Failed("find_contact's newest result has no contact ${delete.contact}"))
        }
        while (true) {
            if (run.gate()) return Done(call.tool, text = "Result: ${ScreenSteps.PAUSED}", summary = "Step ${run.replies}: the task was paused; nothing was done.", last = "paused; nothing was done", after = run.fresh(), progress = false, executed = false)
            val before = run.fresh()
            var proposal = ActionMapper.proposal(call, run.observation.table, run.observation.appLabel, receivingPackage = sharePackage, receivingAppLabel = receivingLabel)
            if (call is AgentCall.ShareMedia) proposal = proposal.copy(shareCount = count(call.items))
            if (contact != null) proposal = proposal.copy(contactName = contact.name, contactNumber = contact.number)
            val go = when (val clearance = run.decide(proposal, before, call.tool, null)) {
                Clearance.Again -> continue
                is Clearance.Stop -> return clearance.done
                is Clearance.Go -> clearance
            }
            val front = ScreenTree.frontWindow(before)?.packageName
            val result = runAllowed(call, sharePackage, front, contact?.ref) ?: return Done(call.tool, Outcome.PermissionRefused)
            if (result is DataResult.Failed) return Done(call.tool, Outcome.Failed(result.reason), detail = detail(call))
            // The app's screen that the tool prepared opens only through the launch check (plan 6B-basefix).
            val launched = (result as? DataResult.Opens)?.let { opens ->
                val launch = when (val checked = run.launches.intent(opens.intent)) {
                    is Launches.Launch.Stop -> return Done(call.tool, checked.outcome, detail = detail(call))
                    is Launches.Launch.Go -> checked.launch
                }
                if (!run.ports.screen.act(ScreenAction.Launch(launch)).done) return Done(call.tool, Outcome.Failed(NOT_OPENED), detail = detail(call))
                Opened(launch.target.takeIf { opens.front }, opens.done)
            }
            val ok = launched?.let { DataResult.Ok(listOf(DataLine(it.done, emptyList()))) } ?: result as DataResult.Ok
            remember(call, ok)
            if (contact != null) deleted(go, contact)
            // A share or a new SMS always starts the share flow, also when Android names no app for it (D14).
            val opens = call is AgentCall.ShareMedia || call is AgentCall.ComposeSms
            val opened = launched?.front
            val after = if (opens || opened != null) {
                run.ports.screen.waitForFront(opened ?: sharePackage, ScreenSteps.FRONT_WAIT_MILLIS, before = front)
                run.fresh().also { run.safety = SafetyCheck.afterAction(proposal, before, it, run.safety) }
            } else {
                null
            }
            val outcome = if (ok.lines.isEmpty()) Outcome.DataEmpty else Outcome.DataOk(ok.lines.size)
            val notes = sharePackage?.let { run.recalled(null) { run.memory.notesFor(it) } }?.let { Redacted.kestrel(it) }
            // add_contact adds nothing when the contact already has that number, and the report must not say it did.
            val unchanged = call is AgentCall.AddContact && ok.lines.any { it.fixed == DataPort.ADDED_NOTHING }
            return Done(call.tool, outcome, detail = detail(call), data = DataResultText.of(ok, run.redactor), after = after, extra = notes, unchanged = unchanged)
        }
    }

    // Runs the tool; asks for a missing permission or access once and runs it again. Null: the owner refused (D12).
    private suspend fun runAllowed(call: AgentCall, sharePackage: String?, front: String?, contactRef: String? = null): DataResult? {
        val today = run.ports.clock.now().toLocalDate()
        val first = ActionMapper.runData(call, run.ports.data, today, sharePackage, contactRef) ?: return DataResult.Failed(NOT_DATA)
        when (first) {
            is DataResult.NeedsPermission -> {
                val end = run.limits.waiting { run.ports.owner.askPermission(first.permissions, why(call)) }
                if (end == PermissionEnd.REFUSED) return null
            }
            is DataResult.NeedsAccess -> {
                when (run.limits.waiting { run.ports.owner.handOver(HandOver.of(HandOverKind.ACCESS)) }) {
                    HandOverEnd.CANCELLED -> throw Ended(EndReason.CANCELLED)
                    HandOverEnd.EXPIRED -> throw Ended(EndReason.HAND_OVER_EXPIRED)
                    else -> Unit
                }
            }
            else -> return first
        }
        // Back to the task's app before the tool runs again (D12, D15).
        if (front != null && !run.ports.screen.waitForFront(front, ScreenSteps.FRONT_WAIT_MILLIS)) {
            (run.launches.app(front) as? Launches.Launch.Go)?.let { run.ports.screen.act(ScreenAction.Launch(it.launch)) }
            run.ports.screen.waitForFront(front, ScreenSteps.FRONT_WAIT_MILLIS)
        }
        val again = ActionMapper.runData(call, run.ports.data, today, sharePackage, contactRef) ?: return DataResult.Failed(NOT_DATA)
        return when (again) {
            is DataResult.NeedsPermission -> null
            is DataResult.NeedsAccess -> DataResult.Failed(NO_ACCESS)
            else -> again
        }
    }

    private suspend fun sharePackage(call: AgentCall.ShareMedia): String? {
        val known = SharePackages.of(call.app)
        if (known.isNotEmpty()) {
            val installed = run.ports.apps.launcherApps().map { it.packageName }.toSet()
            return known.firstOrNull { it in installed }
        }
        val pick = NamePick.pick(call.app, run.ports.apps.launcherApps(), { it.label }, { false })
        return (pick as? Pick.One)?.item?.packageName
    }

    private fun unknownApp(call: AgentCall.ShareMedia): Done =
        Done(call.tool, Outcome.Failed("no single installed app named ${quoted(call.app)}"))

    // Memory's aliases come first (D12, D22): "mom = contact Mama" before `find_contact`, "home = …" before `navigate`.
    private fun resolved(call: AgentCall): AgentCall = when (call) {
        is AgentCall.FindContact -> run.memory.alias(call.name)?.let { call.copy(name = it) } ?: call
        is AgentCall.Navigate -> run.memory.alias(call.to)?.let { call.copy(to = it) } ?: call
        else -> call
    }

    // What the task learns from a result: the contacts that it found (D32 item 44) and find_media's kinds per item.
    private suspend fun remember(call: AgentCall, ok: DataResult.Ok) {
        if (call is AgentCall.FindContact) {
            run.remembering { run.memory.foundContacts(ok.lines.flatMap { line -> line.fields.filter { it.name == NAME_FIELD }.map { it.value } }) }
            found = ok.lines.map { line ->
                FoundContact(
                    line.ref,
                    line.fields.firstOrNull { it.name == NAME_FIELD }?.value.orEmpty(),
                    line.fields.firstOrNull { it.name == PHONE_FIELD }?.value?.substringBefore(" ("),
                )
            }
        }
        if (call is AgentCall.FindMedia) {
            mediaNote = ok.note
            for (line in ok.lines) {
                val id = line.fixed.substringBefore(' ').trim()
                videos[id] = "video" in Normalizer.words(line.fixed)
            }
        }
    }

    // A contact that delete_contact deleted counts as a delete on the screen does (design D19 of sub-project 6): after the
    // owner's yes as a confirmed delete, which the report names; without one, only in Full-auto, as an unconfirmed one.
    private fun deleted(go: Clearance.Go, contact: FoundContact) {
        val yes = go.confirmation
        if (yes != null) {
            run.sent(yes, share = false, items = 0, covered = false)
            return
        }
        if (go.category == Category.DELETING) run.unconfirmed++
        run.ledger.add("deleted ${contact.name}")
    }

    private fun count(items: List<String>): ShareCount {
        val video = items.count { videos[it] == true }
        return ShareCount(photos = items.size - video, videos = video)
    }

    /** The short argument text of a data step, which its result line and summary name (D6); already redacted. */
    private fun detail(call: AgentCall): String? = when (call) {
        is AgentCall.FindContact -> run.redactor.redact(call.name)
        is AgentCall.AddContact -> run.redactor.redact(call.name)
        is AgentCall.DeleteContact -> "contact ${call.contact}"
        is AgentCall.FindMedia -> listOfNotNull(call.day, call.until?.let { "to $it" }).joinToString(" ") + ", " + call.kinds.name.lowercase(Locale.ROOT)
        is AgentCall.ShareMedia -> "${call.items.size} items"
        is AgentCall.ListEvents -> "${call.from} to ${call.to}"
        is AgentCall.CreateEvent -> run.redactor.redact(call.title)
        is AgentCall.SetAlarm -> "%02d:%02d".format(Locale.ROOT, call.hour, call.minute)
        is AgentCall.SetTimer -> "${call.seconds} s"
        is AgentCall.ListSms -> call.from?.let { run.redactor.redact(it) }
        is AgentCall.MediaControl -> call.action.name.lowercase(Locale.ROOT)
        is AgentCall.Navigate -> call.app.name.lowercase(Locale.ROOT)
        else -> null
    }

    /** The ledger's line for a data step that changed something the owner can see (D24). */
    fun ledgerLine(done: Done): String? {
        if (done.outcome !is Outcome.DataOk && done.outcome != Outcome.DataEmpty || done.unchanged) return null
        return when (done.tool) {
            Tools.SHARE_MEDIA -> "opened the share screen with ${done.detail}"
            Tools.COMPOSE_SMS -> "opened a new SMS"
            Tools.NAVIGATE -> "opened the route in ${if (done.detail == "waze") "Waze" else "Google Maps"}"
            Tools.CREATE_EVENT -> "opened a new calendar event"
            Tools.ADD_CONTACT -> "added the contact ${done.detail}"
            Tools.SET_ALARM -> "set an alarm for ${done.detail}"
            Tools.SET_TIMER -> "set a timer"
            Tools.MEDIA_CONTROL -> "pressed ${done.detail}"
            else -> null
        }
    }

    private fun why(call: AgentCall): String = when (call) {
        is AgentCall.FindContact -> "To find a contact, Kestrel needs access to your contacts."
        is AgentCall.AddContact, is AgentCall.DeleteContact -> "To add or delete a contact, Kestrel needs access to your contacts."
        is AgentCall.FindMedia, is AgentCall.ShareMedia -> "To find your photos and videos, Kestrel needs access to them."
        is AgentCall.ListEvents -> "To read your calendar, Kestrel needs access to it."
        is AgentCall.ListSms -> "To read your messages, Kestrel needs access to your SMS."
        is AgentCall.ListCalls -> "To read your call history, Kestrel needs access to it."
        is AgentCall.GetLocation -> "To find where you are, Kestrel needs access to your location."
        else -> "Kestrel needs a permission for this step."
    }

    private fun quoted(text: String): String = ScreenText.quote(run.redactor.redact(text), ScreenText.LABEL_LIMIT)

    override fun toString(): String = "DataSteps(items=${videos.size})"

    companion object {
        const val MAX_SILENT_SHARE = 10

        /** The field of a `find_contact` line that holds the contact's name (5B's contract). */
        const val NAME_FIELD = "name"

        /** The field of a `find_contact` line that holds a number with its kind, as "+1 202 555 0143 (mobile)". */
        const val PHONE_FIELD = "phone"
        private const val NOT_DATA = "this is not a data tool"
        private const val NOT_OPENED = "the app did not open"
        private const val NO_ACCESS = "Kestrel still has no notification access"
    }
}
