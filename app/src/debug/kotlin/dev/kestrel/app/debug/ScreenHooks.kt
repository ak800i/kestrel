package dev.kestrel.app.debug

import android.content.Intent
import android.os.Build
import dev.kestrel.app.AppGraph
import dev.kestrel.app.commands.TextFields
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hook H3 of plan 4D: screen-has checks Kestrel's snapshot of the screen on the phone ([ScreenQuery]) and can send a
 * tagged transcript built from a match's name on the phone, and field compares a text field with an expected text on the
 * phone. Their answers hold counts, numbers, bounds, flags and hashes, never screen text (2026-11-20-a71-checks-4d-interfaces.md,
 * section 5).
 */
internal class ScreenHooks(private val graph: AppGraph) {
    suspend fun run(command: String, intent: Intent): String {
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
        return when (command) {
            "SCREEN_HAS" -> screenHas(session, intent)
            "FIELD" -> field(session, intent)
            else -> throw HookError("unknown command '$command'")
        }
    }

    private suspend fun screenHas(session: ScreenSession, intent: Intent): String {
        val states = try {
            ScreenQuery.states(intent.getStringExtra(EXTRA_STATE))
        } catch (e: IllegalArgumentException) {
            throw HookError("add --es state with some of ${ScreenQuery.STATES.joinToString()}")
        }
        val filter = ScreenQuery.Filter(
            packageName = intent.getStringExtra(EXTRA_PACKAGE),
            viewId = intent.getStringExtra(EXTRA_VIEW_ID),
            label = intent.getStringExtra(EXTRA_LABEL),
            contains = intent.getStringExtra(EXTRA_CONTAINS),
            className = intent.getStringExtra(EXTRA_CLASS),
            states = states,
        )
        val template = intent.getStringExtra(EXTRA_TEMPLATE)
        if (template != null && LABEL !in template) throw HookError("add $LABEL to the template")
        val snapshot = session.snapshots.current().snapshot
        val badges = withContext(Dispatchers.Main) { session.numberBadges }
        val answer = ScreenQuery.answer(snapshot, badges, filter)
        if (template == null) return answer
        val tag = intent.getStringExtra(EXTRA_TAG) ?: throw HookError("add --es tag <tag> to send the template")
        // The name stays on the phone: it goes only into the transcript that Kestrel hears.
        val name = ScreenQuery.matches(snapshot, filter).firstOrNull()?.let { ScreenQuery.firstName(it) }
            ?: throw HookError("nothing on the screen matches")
        val text = template.replace(LABEL, name)
        if (VoiceHooks.refused(text, Build.HARDWARE)) throw HookError(VoiceHooks.YES_CALL_REFUSED)
        val event = Event.Heard(text, named = false)
        VoiceHooks.tag(graph, tag, event)
        graph.listening.send(event)
        return "$answer sent=$tag"
    }

    // The focused text field, or the first reachable one whose view ID contains --es viewId, compared with --es expected.
    private suspend fun field(session: ScreenSession, intent: Intent): String {
        val expected = intent.getStringExtra(EXTRA_EXPECTED) ?: throw HookError("add --es expected <text>")
        val viewId = intent.getStringExtra(EXTRA_VIEW_ID)
        val snapshot = session.snapshots.current().snapshot
        val element = if (viewId == null) {
            TextFields.focused(snapshot)
        } else {
            snapshot.elements.firstOrNull {
                it.editable && snapshot.reachable(it) && it.viewId?.substringAfter(":id/")?.contains(viewId) == true
            }
        }
        val state = element?.let { session.text.read(it.key) } ?: return "field=none"
        // A password field shows only dots, so it compares nothing.
        val equal = !state.password && state.text == expected
        return "field=found equal=$equal length=${state.text.length} start=${state.selectionStart} end=${state.selectionEnd} " +
            "password=${state.password}"
    }

    companion object {
        val COMMANDS = setOf("SCREEN_HAS", "FIELD")

        private const val LABEL = "{label}"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_VIEW_ID = "viewId"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_CONTAINS = "contains"
        private const val EXTRA_CLASS = "class"
        private const val EXTRA_STATE = "state"
        private const val EXTRA_TEMPLATE = "template"
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_EXPECTED = "expected"
    }
}
