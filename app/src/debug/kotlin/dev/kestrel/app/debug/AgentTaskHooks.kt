package dev.kestrel.app.debug

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager
import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.memory.FactKind
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.MediaKinds
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.TaskAsk
import dev.kestrel.app.orchestrator.TaskPhase
import dev.kestrel.datatools.AndroidDataPort
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.EmergencyGuard
import dev.kestrel.safety.SelfChats
import dev.kestrel.screen.act.TouchBranch
import dev.kestrel.screen.act.TouchRule
import dev.kestrel.screen.act.TouchSource
import dev.kestrel.screen.model.ScreenTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate

/**
 * The agent's debug hooks of plan 5B (design D28 of sub-project 5; plan-5b-interfaces.md, section 4), which
 * scripts\task-check.ps1 and plan 5C's sittings use: a tagged task's result and state, its answers, the scripted model,
 * the cache fields, the touch rule, test mode's names, test aliases, one data tool's counts, test mode's recogniser, the
 * screen's structure and a script's own tap. Each answers one line of `name=value` pairs, never screen text, a prompt,
 * a reply, a name or a tool's arguments; [AgentHookAnswers] builds the lines.
 */
internal class AgentTaskHooks(private val graph: AppGraph, private val context: Context) {
    // agent-task -Result -Tag k [--es marker] [--es recipient]
    fun task(intent: Intent): String {
        if (!intent.getBooleanExtra(EXTRA_RESULT, false)) throw HookError("add --ez result true")
        val (_, number) = taggedTask(intent)
        val runner = graph.agent
        val ended = runner.ended(number)
        if (ended == null || runner.running(number)) return "state=running"
        return AgentHookAnswers.result(ended.report, ended.spoken, intent.getStringExtra(EXTRA_MARKER), intent.getStringExtra(EXTRA_RECIPIENT))
    }

    // agent-answer -Tag k --es answer <yes|no|confirm|cancel|continue|n|text:…> [--es confirming <category>], as the owner's
    // voice would answer; an answer to a confirmation other than no or cancel needs the category that agent-state read.
    fun answer(intent: Intent): String {
        val (tag, number) = taggedTask(intent)
        val reply = intent.getStringExtra(EXTRA_ANSWER) ?: throw HookError("add --es answer with yes, no, confirm, cancel, continue, a number or text:<words>")
        val words = AgentHookAnswers.words(reply) ?: throw HookError("add --es answer with yes, no, confirm, cancel, continue, a number or text:<words>")
        if (VoiceHooks.refused(words, Build.HARDWARE)) throw HookError(VoiceHooks.YES_CALL_REFUSED)
        val state = graph.listening.state.value
        val asking = state.taskAsking.takeIf { state.task == number }
        val handedOver = state.task == number && state.taskPhase == TaskPhase.HANDED_OVER
        // A script never answers another task's question (design D28; review C2-I3).
        if (asking == null && !handedOver) throw HookError("the task of tag $tag asks nothing")
        val confirming = graph.agent.confirming(number)?.takeIf { asking == TaskAsk.CONFIRM }
        AgentHookAnswers.answerRefusal(asking, words, confirming, intent.getStringExtra(EXTRA_CONFIRMING))?.let { throw HookError(it) }
        val event = if (asking == null) {
            // A hand-over is not a question: it ends with "Kestrel, continue" or "cancel" (design D2).
            when (words) {
                "continue" -> Event.Heard("Kestrel, continue", named = true)
                "cancel" -> Event.Heard("cancel", named = false)
                else -> throw HookError("a hand-over takes only continue or cancel")
            }
        } else {
            Event.Heard(words, named = false)
        }
        graph.listening.send(event)
        return "answered tag=$tag asking=${asking?.let(::kind) ?: "hand-over"}"
    }

    // agent-script --es file <name> | --ez clear true
    suspend fun script(intent: Intent): String {
        if (intent.getBooleanExtra(EXTRA_CLEAR, false)) {
            graph.agent.modelOverride = null
            return "script=none"
        }
        val name = intent.getStringExtra(EXTRA_FILE)?.takeIf { SCRIPT_NAME.matches(it) }
            ?: throw HookError("add --es file with the name of a script in files/agent-scripts, such as task.json")
        val text = withContext(Dispatchers.IO) {
            File(File(context.filesDir, SCRIPTS), name).takeIf { it.isFile }?.readText()
        } ?: throw HookError("files/$SCRIPTS has no script $name")
        val replies = try {
            Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
        } catch (e: IllegalArgumentException) {
            throw HookError("the script is not a JSON array of replies")
        }
        val model = ScriptedModel(replies)
        graph.agent.modelOverride = model
        return "script=installed replies=${model.left}"
    }

    // agent-state -Tag k
    fun state(intent: Intent): String {
        val (_, number) = taggedTask(intent)
        val state = graph.listening.state.value
        val current = state.task == number && state.taskPhase != null && graph.agent.running(number)
        val phase = if (!current) "ended" else when (state.taskPhase) {
            TaskPhase.HANDED_OVER -> "handed-over"
            TaskPhase.PAUSED -> "paused"
            else -> "running"
        }
        val asking = state.taskAsking?.takeIf { current }?.let(::kind) ?: "none"
        val handOver = graph.agent.handOver(number)?.takeIf { current }?.name ?: "none"
        // The category of the open confirmation, so that task-check.ps1 confirms only the kind that its task names (design D28).
        val confirming = graph.agent.confirming(number)?.takeIf { current }?.wire ?: "none"
        return "phase=$phase asking=$asking work=${state.work.name.lowercase().replace('_', '-')} handOver=$handOver confirming=$confirming"
    }

    // agent-cache --es field <anthropicMarks|responsesExplicit|chatCopilotMarks> --ez on <true|false>
    suspend fun cache(intent: Intent): String {
        val field = intent.getStringExtra(EXTRA_FIELD)
        if (field != null) {
            if (!intent.hasExtra(EXTRA_ON)) throw HookError("add --ez on true or --ez on false")
            val on = intent.getBooleanExtra(EXTRA_ON, true)
            val fields = graph.settings.get(SettingKeys.AGENT_CACHE_FIELDS)
            val changed = when (field.lowercase()) {
                "anthropicmarks" -> fields.copy(anthropicMarks = on)
                "responsesexplicit" -> fields.copy(responsesExplicit = on)
                "chatcopilotmarks" -> fields.copy(chatCopilotMarks = on)
                else -> throw HookError("add --es field with anthropicMarks, responsesExplicit or chatCopilotMarks")
            }
            graph.settings.set(SettingKeys.AGENT_CACHE_FIELDS, changed)
        }
        val fields = graph.settings.get(SettingKeys.AGENT_CACHE_FIELDS)
        return "anthropicMarks=${onOff(fields.anthropicMarks)} responsesExplicit=${onOff(fields.responsesExplicit)} " +
            "chatCopilotMarks=${onOff(fields.chatCopilotMarks)}"
    }

    // agent-touch-source [--es rule <NONE|FIELD|CREDITS> --es branch <A|B>]: stored, and given to the connected session.
    suspend fun touchSource(intent: Intent): String {
        val rule = intent.getStringExtra(EXTRA_RULE)
        val branch = intent.getStringExtra(EXTRA_BRANCH)
        if (rule != null || branch != null) {
            val stored = graph.settings.get(SettingKeys.TOUCH_RULE)
            val source = TouchSource(
                rule?.let { name -> TouchRule.entries.firstOrNull { it.name == name.uppercase() } ?: throw HookError("add --es rule with NONE, FIELD or CREDITS") }
                    ?: stored.rule,
                branch?.let { name -> TouchBranch.entries.firstOrNull { it.name == name.uppercase() } ?: throw HookError("add --es branch with A or B") }
                    ?: stored.branch,
            )
            graph.settings.set(SettingKeys.TOUCH_RULE, source)
            graph.screen.value?.touchSource = source
        }
        val source = graph.settings.get(SettingKeys.TOUCH_RULE)
        return "rule=${source.rule} branch=${source.branch} sdk=${Build.VERSION.SDK_INT}"
    }

    // agent-settings [--es ownerEmails a,b] [--es viberSelfChatName t] [--es whatsappOwnName t] [--es selfChatAliases pkg=pkg]
    suspend fun settings(intent: Intent): String {
        // Only its named keys; another key is refused by its name, never with its value (design D18; review B3 nit 3).
        intent.extras?.keySet()?.firstOrNull { it !in SETTINGS_KEYS }?.let { throw HookError("agent-settings has no key $it") }
        // The aliases are read first, so that an invalid one leaves every setting as it was.
        val aliases = intent.getStringExtra(KEY_ALIASES)?.let { text ->
            try {
                AgentHookAnswers.aliases(text)
            } catch (e: IllegalArgumentException) {
                throw HookError("add --es selfChatAliases as pkg=pkg,pkg=pkg")
            }
        }
        var safety = graph.settings.get(SettingKeys.SAFETY)
        intent.getStringExtra(KEY_EMAILS)?.let { text -> safety = safety.copy(ownerEmails = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }) }
        intent.getStringExtra(KEY_VIBER)?.let { safety = safety.copy(viberSelfChatName = it.trim().ifEmpty { VIBER_DEFAULT }) }
        intent.getStringExtra(KEY_WHATSAPP)?.let { safety = safety.copy(whatsappOwnName = it.trim().ifEmpty { null }) }
        graph.settings.set(SettingKeys.SAFETY, safety)
        aliases?.let { graph.agent.selfChatAliases = it }
        return "ownerEmails=${safety.ownerEmails.size} viberSelfChatName=${if (safety.viberSelfChatName != VIBER_DEFAULT) "set" else "unset"} " +
            "whatsappOwnName=${if (safety.whatsappOwnName != null) "set" else "unset"} selfChatAliases=${graph.agent.selfChatAliases.size}"
    }

    // agent-memory --ez add true --es key k --es text v | --ez remove true --es key k | --ez has true --es key k: a test alias,
    // answered by keys only; has answers only whether a fact with the key exists (design D17 of sub-project 6).
    suspend fun memory(intent: Intent): String {
        val key = intent.getStringExtra(EXTRA_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: throw HookError("add --es key <key>")
        val memory = graph.memory
        return when {
            intent.getBooleanExtra(EXTRA_ADD, false) -> {
                val value = intent.getStringExtra(EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() } ?: throw HookError("add --es text <value>")
                memory.saveFact(Fact(key, value, FactKind.ALIAS, System.currentTimeMillis()))
                "keys=${memory.facts().size} added=${key.replace(Regex("\\s+"), "_")}"
            }
            intent.getBooleanExtra(EXTRA_REMOVE, false) -> {
                memory.deleteFact(key)
                "keys=${memory.facts().size}"
            }
            intent.getBooleanExtra(EXTRA_HAS, false) -> "has=${AgentHookAnswers.has(memory.facts().map { it.key }, key)}"
            else -> throw HookError("add --ez add true, --ez remove true or --ez has true")
        }
    }

    // agent-data --es tool <list_sms|list_calls|find_media|list_notifications> [--es text <argument>]: counts only.
    suspend fun data(intent: Intent): String {
        val argument = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        val agentFree = AgentFreeApps.from(graph.settings.get(SettingKeys.AGENT_FREE_APPS))
        val port = AndroidDataPort(context, open = { false }, isAgentFree = agentFree::isAgentFree)
        val tool = intent.getStringExtra(EXTRA_TOOL)
        val result = when (tool) {
            "list_sms" -> port.listSms(argument, LIMIT)
            "list_calls" -> port.listCalls(LIMIT)
            "find_media" -> {
                val day = when (argument ?: "yesterday") {
                    "today" -> LocalDate.now()
                    "yesterday" -> LocalDate.now().minusDays(1)
                    else -> runCatching { LocalDate.parse(argument) }.getOrNull() ?: throw HookError("add --es text with today, yesterday or YYYY-MM-DD")
                }
                port.findMedia(day, day, MediaKinds.BOTH)
            }
            "list_notifications" -> port.listNotifications()
            else -> throw HookError("add --es tool with list_sms, list_calls, find_media or list_notifications")
        }
        port.forgetTask()
        return when (result) {
            is DataResult.Ok -> {
                // find_media ends a long list with a count line without fields, which is not an item.
                val items = if (tool == "find_media") result.lines.count { MEDIA_ITEM.containsMatchIn(it.fixed) } else result.lines.size
                "result=${if (items == 0) "empty" else "ok"} items=$items"
            }
            is DataResult.NeedsPermission -> "result=permission items=0"
            is DataResult.NeedsAccess -> "result=access items=0"
            is DataResult.Failed -> "result=failed items=0"
        }
    }

    // selfchat-check: test mode's recogniser on the chat that the screen shows, with the debug aliases (design D18).
    suspend fun selfChat(): String {
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
        val snapshot = session.snapshots.current().snapshot
        val aliases = graph.agent.selfChatAliases
        val yes = SelfChats.recognise(snapshot, null, graph.settings.get(SettingKeys.SAFETY), aliases = aliases)
        val front = ScreenTree.frontWindow(snapshot)?.packageName
        val rule = aliases[front]?.takeIf { front == SelfChats.FIXTURES } ?: front
        return "selfchat=${if (yes) "yes" else "no"} signs=${if (yes) signs(rule) else "none"}"
    }

    // agent-tree: the screen's structure into cache/agent-tree.json, with labels only where they are safe (review C I10).
    suspend fun tree(): String {
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
        val snapshot = session.snapshots.current().snapshot
        val safety = graph.settings.get(SettingKeys.SAFETY)
        val safe = AgentHookAnswers.FIXED_SAFE + listOfNotNull(safety.whatsappOwnName?.let { "$it (You)" }, safety.viberSelfChatName)
        val json = buildJsonObject {
            put("windows", buildJsonArray {
                for (window in snapshot.windows) add(buildJsonObject {
                    put("id", window.id)
                    put("kind", window.kind.name)
                    put("packageName", window.packageName)
                    put("active", window.active)
                    put("bounds", bounds(window.bounds.left, window.bounds.top, window.bounds.right, window.bounds.bottom))
                })
            })
            put("elements", buildJsonArray {
                for (element in snapshot.elements) add(buildJsonObject {
                    put("windowId", element.windowId)
                    element.parent?.let { put("parent", it) }
                    put("packageName", element.packageName)
                    put("className", element.className)
                    put("viewId", element.viewId)
                    put("bounds", bounds(element.bounds.left, element.bounds.top, element.bounds.right, element.bounds.bottom))
                    put("clickable", element.clickable)
                    put("editable", element.editable)
                    put("checkable", element.checkable)
                    put("checked", element.checked)
                    put("scrollable", element.scrollable)
                    put("password", element.password)
                    val label = element.label
                    val shown = AgentHookAnswers.safeLabel(label, element.packageName, safe)
                    if (shown != null) put("label", shown) else if (label != null) put("labelLength", label.length)
                    put("status", AgentHookAnswers.status(label))
                })
            })
        }
        withContext(Dispatchers.IO) { File(context.cacheDir, TREE_FILE).writeText(json.toString()) }
        return "tree=written path=${context.cacheDir.name}/$TREE_FILE windows=${snapshot.windows.size} elements=${snapshot.elements.size}"
    }

    // screen-tap --es label <text>: the script's own tap on the element whose label equals the text, compared on the phone.
    suspend fun tap(intent: Intent): String {
        val label = intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotEmpty() } ?: throw HookError("add --es label <text>")
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
        val snapshot = session.snapshots.current().snapshot
        val front = ScreenTree.frontWindow(snapshot) ?: return "tapped=false count=0"
        val matches = snapshot.elements.indices.filter { snapshot.elements[it].windowId == front.id && snapshot.elements[it].label == label }
        val phoneApp = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        // Never what the agent would press only after the owner's yes or never, so that a script's tap can never place a
        // call (process rule 10). The answer names no label.
        if (AgentHookAnswers.tapRefused(snapshot, matches.firstOrNull(), label, EmergencyGuard.PHONE_PACKAGES + listOfNotNull(phoneApp))) {
            return "tapped=false error=refused"
        }
        val target = matches.firstOrNull()?.let { snapshot.elements[it] } ?: return "tapped=false count=0"
        val done = session.actions.tap(target.key) is dev.kestrel.screen.act.ActionResult.Done
        return "tapped=$done count=${matches.size}"
    }

    // The task number of the transcript with the hook's tag (plan 4D's H1).
    private fun taggedTask(intent: Intent): Pair<String, Int> {
        val tag = intent.getStringExtra(EXTRA_TAG) ?: throw HookError("add --es tag <tag>")
        val number = VoiceHooks.tags.tagged(tag)?.task?.takeIf { it > 0 } ?: throw HookError("no task has the tag $tag")
        return tag to number
    }

    private fun kind(ask: TaskAsk): String = ask.name.lowercase().replace('_', '-')

    private fun signs(rule: String?): String = when (rule) {
        SelfChats.TELEGRAM, SelfChats.TELEGRAM_WEB -> "telegram-title,telegram-toolbar"
        SelfChats.WHATSAPP -> "whatsapp-title,whatsapp-subtitle"
        SelfChats.VIBER -> "viber-title,viber-notes"
        SelfChats.GMAIL -> "gmail-recipients"
        else -> "none"
    }

    private fun bounds(left: Int, top: Int, right: Int, bottom: Int): JsonArray = buildJsonArray {
        add(kotlinx.serialization.json.JsonPrimitive(left))
        add(kotlinx.serialization.json.JsonPrimitive(top))
        add(kotlinx.serialization.json.JsonPrimitive(right))
        add(kotlinx.serialization.json.JsonPrimitive(bottom))
    }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    companion object {
        val COMMANDS = setOf(
            "AGENT_TASK", "AGENT_ANSWER", "AGENT_SCRIPT", "AGENT_STATE", "AGENT_CACHE", "AGENT_TOUCH_SOURCE", "AGENT_SETTINGS",
            "AGENT_MEMORY", "AGENT_DATA", "SELFCHAT_CHECK", "AGENT_TREE", "SCREEN_TAP",
        )
        const val SCRIPTS = "agent-scripts"
        const val TREE_FILE = "agent-tree.json"
        private const val VIBER_DEFAULT = "My Notes"
        private const val LIMIT = 20
        private val SCRIPT_NAME = Regex("[A-Za-z0-9_.-]{1,64}\\.json")
        private val MEDIA_ITEM = Regex("^m\\d+\\b")
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_MARKER = "marker"
        private const val EXTRA_RECIPIENT = "recipient"
        private const val EXTRA_ANSWER = "answer"
        private const val EXTRA_CONFIRMING = "confirming"
        private const val EXTRA_CLEAR = "clear"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_FIELD = "field"
        private const val EXTRA_ON = "on"
        private const val EXTRA_RULE = "rule"
        private const val EXTRA_BRANCH = "branch"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_ADD = "add"
        private const val EXTRA_REMOVE = "remove"
        private const val EXTRA_HAS = "has"
        private const val EXTRA_TOOL = "tool"
        private const val EXTRA_LABEL = "label"
        private const val KEY_EMAILS = "ownerEmails"
        private const val KEY_VIBER = "viberSelfChatName"
        private const val KEY_WHATSAPP = "whatsappOwnName"
        private const val KEY_ALIASES = "selfChatAliases"
        private val SETTINGS_KEYS = setOf(KEY_EMAILS, KEY_VIBER, KEY_WHATSAPP, KEY_ALIASES)
    }
}
