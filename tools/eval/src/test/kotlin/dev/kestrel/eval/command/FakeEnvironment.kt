package dev.kestrel.eval.command

import dev.kestrel.eval.device.FakeAdb
import dev.kestrel.eval.device.FakeClock
import dev.kestrel.eval.device.Processes
import dev.kestrel.eval.device.ShellResult
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files

/**
 * This computer and a scripted kestrel-api37 for the tests of the commands: Kestrel and `:testapp` installed, its
 * accessibility service on, listening, test mode on, a Copilot profile and one Google account, and a task that ends
 * FINISHED; [processes] records the local programs, `git rev-parse HEAD` answers a fixed commit, `git diff` answers
 * [gitDiff], as git names the changed paths of Kestrel's ([Resume.KESTREL_PATHS]), and `git log -g` answers [reflog]. Kestrel's build was installed at [installed], and the `state` hook names
 * its commit and dirty flag in [build].
 */
class FakeEnvironment {
    val adb = FakeAdb()
    val clock = FakeClock()
    val root = Files.createTempDirectory("eval-root")
    val processes = mutableListOf<List<String>>()
    private val bytes = ByteArrayOutputStream()
    var live = "off"
    var accounts = 1
    var gitDiff = ""
    var reflog = ""
    var installed = "2026-11-24 09:18:59"
    var build = "commit=$COMMIT dirty=0"
    var end = "state=done end=FINISHED steps=3 workMs=9000 credits=0.00 confirmed=0 unconfirmed=0 shareItems=0 beforeTestMode=0 model=scripted"

    val devices = mutableMapOf(adb.serial to adb)
    val environment = Environment(
        root,
        { serial -> devices[serial] ?: FakeAdb(serial = serial, model = "unknown", avd = "") },
        Processes { command, _ ->
            processes.add(command)
            val out = when {
                command.first() != "git" -> ""
                "diff" in command -> gitDiff
                "log" in command -> reflog
                else -> "$COMMIT\n"
            }
            ShellResult(0, out, "")
        },
        clock,
        PrintStream(bytes, true, Charsets.UTF_8),
        {},
    )
    val output: String get() = bytes.toString(Charsets.UTF_8)

    init {
        adb.on("^pm list packages dev.kestrel$") { "package:dev.kestrel" }
        adb.on("^pm list packages dev.kestrel.testapp$") { "package:dev.kestrel.testapp" }
        adb.on("^dumpsys account$") { (1..accounts).joinToString("\n") { "    Account {name=account$it@example.com, type=com.google}" } }
        adb.hook("STATE") { "screen=on testMode=on numbers=0 $build" }
        adb.on("^dumpsys package dev\\.kestrel$") {
            "  Package [dev.kestrel] (316e119):\n    userId=$KESTREL_UID\n    versionCode=100 minSdk=33 targetSdk=37\n    versionName=1.0.0\n    lastUpdateTime=$installed\n" + adb.runtimePermissions("dev.kestrel")
        }
        adb.hook("VOICE_STATE") { "mode=listening work=idle session=open clips=off" }
        adb.hook("PROVIDER_STANDIN") { "standin=off profile=copilot copilot=1" }
        adb.hook("AGENT_LIVE") { extras -> extras["on"]?.let { live = if (it == "true") "on" else "off" }; "live=$live" }
        adb.hook("AGENT_SCRIPT") { extras -> if (extras["clear"] == "true") "script=none" else "script=installed replies=2" }
        adb.hook("VOICE") { "sent" }
        adb.hook("AGENT_STATE") { "phase=running asking=none work=running-task handOver=none confirming=none" }
        adb.hook("AGENT_TASK") { extras -> if (extras["marker"] != null) "state=done marker=1" else end }
        adb.hook("SCREEN_HAS") { "count=1 app=dev.kestrel.testapp" }
        adb.hook("SCREEN_TAP") { "tapped=true count=1" }
    }

    fun cli(vararg args: String): Int = dev.kestrel.eval.Cli(environment).run(args.toList())

    companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        const val KESTREL_UID = 10234
    }
}
