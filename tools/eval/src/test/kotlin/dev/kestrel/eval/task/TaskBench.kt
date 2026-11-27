package dev.kestrel.eval.task

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.run.ConversationGuard
import dev.kestrel.eval.run.LineWriter
import dev.kestrel.eval.run.Markers
import dev.kestrel.eval.run.RunLine
import dev.kestrel.eval.run.RunOptions
import dev.kestrel.eval.run.Runner
import dev.kestrel.eval.run.ScriptInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * A scripted device for the tests of whole tasks (design D4 of sub-project 6): Kestrel's hooks answer as a run that
 * passes, and a test changes one answer to make it fail. [memory] holds the test aliases' keys, so that a test sees that
 * the alias left Kestrel's memory again.
 */
class TaskBench(kind: DeviceKind = DeviceKind.EMULATOR, config: LocalConfig = LocalConfig.EMPTY, private val live: Boolean = false) {
    val run = FakeRun(kind, config = config)
    val adb get() = run.adb
    var end = "state=done end=FINISHED steps=3 workMs=9000 credits=0.00 confirmed=0 unconfirmed=0 shareItems=0 beforeTestMode=0 model=scripted"
    var replyMarker = "1"
    var recipient = "match"
    var selfChat = "yes"
    val memory = mutableSetOf<String>()

    init {
        adb.hook("AGENT_SCRIPT") { extras -> if (extras["clear"] == "true") "script=none" else if (extras["check"] == "true") "script=valid replies=3" else "script=installed replies=3" }
        adb.hook("VOICE") { "sent" }
        adb.hook("AGENT_STATE") { "phase=running asking=none work=running-task handOver=none confirming=none" }
        adb.hook("AGENT_TASK") { extras ->
            when {
                extras["marker"] != null -> "state=done marker=$replyMarker"
                extras["recipient"] != null -> "state=done recipient=$recipient"
                else -> end
            }
        }
        adb.hook("AGENT_MEMORY") { extras ->
            val key = extras.getValue("key")
            when {
                extras["has"] == "true" -> if (key in memory) "has=yes" else "has=no"
                extras["add"] == "true" -> { memory.add(key); "keys=${memory.size} added=$key" }
                else -> { memory.remove(key); "keys=${memory.size}" }
            }
        }
        adb.hook("AGENT_SETTINGS") { "ownerEmails=0 viberSelfChatName=unset whatsappOwnName=unset selfChatAliases=0" }
        adb.hook("SELFCHAT_CHECK") { "selfchat=$selfChat signs=telegram-title" }
    }

    /** One run of [task] with the scripted model of its dry run, or live. */
    fun run(task: Task): RunLine {
        val runner = Runner(
            run.device, run.hooks, run.restores, run.clock, run.console, run.scope.config, run.scope.work, LineWriter(run.console, null),
            RunOptions(live), {}, ConversationGuard(run.hooks, run.clock), Markers(),
        )
        return runner.run(task, if (live) null else ScriptInstaller.resource(task.dryRun!!.script), 1, "maple heron")
    }

    /** Every restore ran, and no test alias is left. */
    fun assertPutBack() {
        assertEquals(0, run.restores.size)
        assertTrue(memory.isEmpty())
    }
}
