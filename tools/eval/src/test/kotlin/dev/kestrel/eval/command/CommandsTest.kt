package dev.kestrel.eval.command

import dev.kestrel.eval.Cli
import dev.kestrel.eval.device.FakeAdb
import dev.kestrel.eval.report.Report
import dev.kestrel.eval.report.SuiteCall
import dev.kestrel.eval.run.EvalAccess
import dev.kestrel.eval.run.EvalPermissions
import dev.kestrel.eval.task.StandIns
import dev.kestrel.eval.task.ZenDump
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The commands, their exit codes and their results files (design D2, D6 and D10 of sub-project 6), on a scripted kestrel-api37. */
class CommandsTest {
    private val fake = FakeEnvironment()

    private fun runLines(): List<String> = fake.output.lines().filter { it.startsWith("task=") }

    @Test
    fun aCommandLineThatTheToolDoesNotTakeIsRefusedWithTheUsage() {
        for (args in listOf(emptyList(), listOf("fly"), listOf("run", "--task", "hotspot"), listOf("run", "--device", "emulator-5554", "--task", "hotspot", "--runs", "11"),
            listOf("run", "--device", "emulator-5554", "--task", "hotspot", "--live", "--dry-run"), listOf("suite", "--device", "emulator-5554", "--name", "Ana"))) {
            assertEquals(args.toString(), Cli.EXIT_REFUSED, fake.cli(*args.toTypedArray()))
        }
        assertTrue(fake.output.contains("Usage: scripts\\eval.ps1 <command> [options]"))
        assertTrue(fake.adb.commands.isEmpty())
    }

    @Test
    fun theS23PlusAndAnUnknownTaskAreRefusedBeforeAnyRun() {
        fake.devices["R5CW00000000"] = FakeAdb(serial = "R5CW00000000", model = "SM-S916B", avd = "", sdk = 36)

        assertEquals(Cli.EXIT_REFUSED, fake.cli("run", "--device", "R5CW00000000", "--task", "hotspot", "--dry-run"))
        assertEquals(Cli.EXIT_REFUSED, fake.cli("run", "--device", "emulator-5554", "--task", "no-such-task"))

        assertTrue(fake.output.contains("REFUSED: Refusing R5CW00000000: it is the owner's Galaxy S23+ (SM-S916B), which :tools:eval never uses."))
        assertTrue(runLines().isEmpty())
    }

    @Test
    fun aDryRunOfAD3TaskRunsItsStandInOnceIntoTheResultsFile() {
        val results = fake.root.resolve("corpus").resolve("owner").resolve("agent-sitting2-dry-run.txt")

        val code = fake.cli("run", "--device", "emulator-5554", "--task", "hotspot", "--runs", "3", "--dry-run", "--results", results.toString())

        assertEquals(Cli.EXIT_PASSED, code)
        val line = Files.readAllLines(results).single()
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\ttask\\t").containsMatchIn(line))
        assertTrue(line.endsWith("\ttask\ttask=stand-in-hotspot run=1 result=pass end=FINISHED check=pass steps=3 workMs=9000 credits=0.00 model=scripted in=0 out=0 cacheRead=0 cacheWrite=0 reason=ok"))
        assertTrue(fake.adb.commands.contains("am start -W -S -n dev.kestrel.testapp/.StandInActivity --es screen 'hotspot'"))
        assertEquals("off", fake.live)
        // The preflight's, the run's and the series' cleanup's: the cleanup knows that the run gave Kestrel a command.
        assertEquals(3, fake.adb.hookCalls("CONVERSATION_CONTROL").count { it["do"] == "delete-typed" })
    }

    // A script that Kestrel's scripted model refuses stops the suite before any run, with the file and Kestrel's reason
    // (plan 7G).
    @Test
    fun aScriptThatKestrelRefusesStopsTheSuiteBeforeAnyRun() {
        fake.adb.hookWith("AGENT_SCRIPT") { extras ->
            if (extras["check"] == "true") 1 to "error: the script is refused (InvalidScript): Reply 1 of the script has keys that the scripted model does not know: require."
            else -1 to "script=installed replies=2"
        }

        assertEquals(Cli.EXIT_REFUSED, fake.cli("suite", "--device", "emulator-5554", "--runs", "2", "--tasks", "stand-in-hotspot,stand-in-firefox"))

        assertTrue(fake.output.contains("REFUSED: Kestrel's scripted model does not take the script "))
        assertTrue(fake.output.contains("(error: the script is refused (InvalidScript): Reply 1 of the script has keys that the scripted model does not know: require.)"))
        assertTrue(runLines().isEmpty())
        assertTrue(fake.adb.hookCalls("VOICE").isEmpty())
        assertTrue(fake.adb.hookCalls("AGENT_SCRIPT").none { it["check"] == null && it["clear"] == null })
    }

    @Test
    fun aTaskWithoutAFormWithoutAModelIsSkippedWithItsReason() {
        assertEquals(Cli.EXIT_PASSED, fake.cli("run", "--device", "emulator-5554", "--task", "waze-home", "--dry-run"))

        assertEquals(listOf("task=waze-home run=1 result=skipped end=none check=skipped steps=0 workMs=0 credits=0.00 model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=untestable"), runLines())
    }

    @Test
    fun aTaskThatTheDeviceDoesNotRunIsSkippedBeforeThePreflightChangesAnything() {
        val a71 = FakeAdb(serial = "R58N66RVD7Z", model = "SM-A715F", avd = "", sdk = 33)
        fake.devices[a71.serial] = a71

        assertEquals(Cli.EXIT_PASSED, fake.cli("run", "--device", "R58N66RVD7Z", "--task", "telegram-late", "--runs", "2"))

        assertEquals(listOf("task=stand-in-messenger-late run=1 result=skipped end=none check=skipped steps=0 workMs=0 credits=0.00 model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=untestable"), runLines())
        assertTrue(fake.processes.isEmpty())
        assertTrue(a71.commands.none { it.startsWith("am ") || it.startsWith("pm install") || it.startsWith("input ") })
    }

    @Test
    fun aTaskThatRunsOnlyWithTheScriptedModelIsSkippedWithLiveBeforeThePreflight() {
        assertEquals(Cli.EXIT_PASSED, fake.cli("run", "--device", "emulator-5554", "--task", "stand-in-typed-contact-then-message", "--runs", "1", "--live"))

        assertEquals(
            listOf("task=stand-in-typed-contact-then-message run=1 result=skipped end=none check=skipped steps=0 workMs=0 credits=0.00 model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=untestable"),
            runLines(),
        )
        assertTrue(fake.output.contains("Task stand-in-typed-contact-then-message runs only with the scripted model, so it does not run with --live."))
        assertTrue(fake.adb.hookCalls("VOICE").isEmpty())
    }

    @Test
    fun aFailedRunEndsWithExitCode1() {
        fake.adb.hook("SCREEN_HAS") { "count=0 app=dev.kestrel.testapp" }

        assertEquals(Cli.EXIT_FAILED, fake.cli("run", "--device", "emulator-5554", "--task", "stand-in-hotspot", "--runs", "2"))
        assertEquals(2, runLines().size)
    }

    @Test
    fun aLiveRunOpensTheLiveSessionAndClosesItAgainAndALiveSessionLeftOnIsTurnedOffWithoutLive() {
        assertEquals(Cli.EXIT_PASSED, fake.cli("run", "--device", "emulator-5554", "--task", "stand-in-hotspot", "--runs", "1", "--live", "--model", "gpt-6-luna"))
        assertEquals(listOf(emptyMap(), mapOf("on" to "true"), mapOf("on" to "false")), fake.adb.hookCalls("AGENT_LIVE"))
        assertEquals("true", fake.adb.hookCalls("VOICE").first()["live"])
        assertEquals("gpt-6-luna", fake.adb.hookCalls("VOICE").first()["model"])
        assertTrue(fake.adb.hookCalls("AGENT_SCRIPT").isEmpty())

        fake.live = "on"
        assertEquals(Cli.EXIT_PASSED, fake.cli("run", "--device", "emulator-5554", "--task", "stand-in-hotspot", "--runs", "1"))
        assertEquals("off", fake.live)
        assertTrue(fake.output.contains("WARNING: The live session on emulator-5554 was on"))
    }

    @Test
    fun aSuiteWritesItsRunsAndItsReportIntoANewFolderThatA71AgentPs1CanRead() {
        val code = fake.cli("suite", "--device", "emulator-5554", "--runs", "2", "--tasks", "stand-in-hotspot,stand-in-firefox")

        assertEquals(Cli.EXIT_PASSED, code)
        val folder = fake.root.resolve("eval-results").resolve("kestrel-api37-20261123-120000")
        val runs = Files.readAllLines(folder.resolve("runs.txt"))
        assertEquals(6, runs.size)
        // The detail lines go into their own file, one per run, so that runs.txt keeps only what a71-agent.ps1 reads.
        assertEquals(4, Files.readAllLines(folder.resolve("details.txt")).count { it.contains("\tdetail\tdetail task=") })
        // a71-agent.ps1's Get-SittingRuns reads the device from these lines, and ConvertFrom-TaskLine the runs.
        assertTrue(Regex("\tstand-in-hotspot on (the A71|kestrel-api37), \\d+ runs$").containsMatchIn(runs[0]))
        assertTrue(runs[0].contains("\tsuite\tstand-in-hotspot on kestrel-api37, 2 runs"))
        assertTrue(runs[1].split('\t').last().startsWith("task=stand-in-hotspot run=1 "))
        val report = Json.decodeFromString(Report.serializer(), Files.readString(folder.resolve("report.json")))
        assertEquals("kestrel-api37", report.device)
        assertEquals("0123456789abcdef0123456789abcdef01234567", report.commit)
        assertEquals(listOf("stand-in-hotspot", "stand-in-firefox"), report.tasks.map { it.task })
        assertEquals(2, report.tasks[0].passes)
        assertEquals(3.0, report.tasks[0].meanSteps, 0.0)
        assertEquals(4, report.totals.runs)
        assertFalse(report.live)
    }

    @Test
    fun aMultiTurnSuiteRunsIntoAFolderOfItsOwnWhoseReportSaysSo() {
        assertEquals(Command.Suite("emulator-5554", multiTurn = true), Arguments.parse(listOf("suite", "--device", "emulator-5554", "--multi-turn")))
        val series = SeriesCommand(fake.environment, SeriesCommand.SUITE, live = false, runs = 1, multiTurn = true) { listOf(StandIns.HOTSPOT) }

        assertEquals(Cli.EXIT_PASSED, series.execute("emulator-5554") {})

        val folder = fake.root.resolve("eval-results").resolve("kestrel-api37-multi-turn-20261123-120000")
        assertEquals(folder, series.folder)
        assertTrue(Report.read(folder.resolve("report.json")).multiTurn)
        assertTrue(Files.readAllLines(folder.resolve("runs.txt")).first().contains("\tsuite\tstand-in-hotspot on kestrel-api37, 1 runs"))
    }

    @Test
    fun recordBaselineIsRefusedBeforeAnyRunWhenKestrelsBuildIsNotHeadsKestrel() {
        val older = "b".repeat(40)
        // A build of another commit whose changes reach Kestrel, a build of HEAD with uncommitted changes, and a build that
        // names no commit, from before its debug build wrote one.
        val builds = listOf(
            "commit=$older dirty=0" to "app/src/main/kotlin/dev/kestrel/app/MainActivity.kt\n",
            "commit=${FakeEnvironment.COMMIT} dirty=1" to "",
            "commit=unknown dirty=unknown" to "",
        )
        for ((build, diff) in builds) {
            val fake = FakeEnvironment()
            fake.build = build
            fake.gitDiff = diff

            assertEquals(build, Cli.EXIT_REFUSED, fake.cli("suite", "--device", "emulator-5554", "--live", "--record-baseline"))

            assertTrue(fake.output, fake.output.contains("--record-baseline records only HEAD's Kestrel"))
            assertTrue(fake.adb.hookCalls("VOICE").isEmpty())
            assertFalse(Files.exists(fake.root.resolve("eval").resolve("baseline.json")))
        }
    }

    @Test
    fun theInjectionFixturesRunOnTheEmulatorOnlyOnceEachWithoutAModel() {
        fake.devices["R58N66RVD7Z"] = FakeAdb(serial = "R58N66RVD7Z", model = "SM-A715F", avd = "", sdk = 33)
        assertEquals(Cli.EXIT_REFUSED, fake.cli("injection", "--device", "R58N66RVD7Z"))
        // records-injection's two commands share a conversation, which the end of its quiet time closes before the next run.
        var commands = 0
        fake.adb.hook("VOICE") {
            commands++
            "sent"
        }
        fake.adb.hook("CONVERSATION_STATE") {
            if (commands == 0) FakeAdb.NO_CONVERSATION
            else "open=yes continued=no commands=$commands goal=none quiet=running quietLeft=20 replyChannel=voice testMode=on typed=yes"
        }
        fake.adb.hook("CONVERSATION_CONTROL") { extras ->
            if (extras["do"] == "expire-typed") {
                commands = 0
                "expire=sent"
            } else {
                FakeAdb.NOTHING_DELETED
            }
        }

        assertEquals(Cli.EXIT_PASSED, fake.cli("injection", "--device", "emulator-5554"))
        assertEquals((1..14).map { "inject-%02d".format(it) } + "records-injection", runLines().map { it.substringAfter("task=").substringBefore(' ') })
        assertTrue(Files.isRegularFile(fake.root.resolve("eval-results").resolve("kestrel-api37-injection-20261123-120000").resolve("report.json")))
    }

    @Test
    fun aRehearsalPassesWhenItsChecksPassAndEveryValueIsBackAndFailsOtherwise() {
        var overlay = "default"
        fake.adb.on("^appops get dev.kestrel.testapp SYSTEM_ALERT_WINDOW$") { if (overlay == "default") "No operations." else "SYSTEM_ALERT_WINDOW: $overlay" }
        fake.adb.on("^appops set dev.kestrel.testapp SYSTEM_ALERT_WINDOW allow$") { overlay = "allow"; "" }

        val code = fake.cli("rehearse", "--device", "emulator-5554", "--tasks", "stand-in-hotspot,stand-in-read,inject-06")

        assertEquals(Cli.EXIT_FAILED, code)
        val lines = fake.output.lines().filter { it.startsWith("rehearse=") }
        assertEquals(
            listOf("rehearse=stand-in-hotspot result=pass reason=ok", "rehearse=stand-in-read result=pass reason=setup-only", "rehearse=inject-06 result=fail reason=changed"),
            lines,
        )
        assertTrue(fake.output.contains("not as before: app-op:SYSTEM_ALERT_WINDOW"))
    }

    @Test
    fun aRehearsalComparesAValueThatTwoStepsReadWithItsValueBeforeTheFirstStep() {
        val contacts = mutableListOf<Int>()
        fake.adb.on("^content query --uri content://com.android.contacts/raw_contacts") {
            contacts.mapIndexed { row, id -> "Row: $row _id=$id" }.joinToString("\n").ifEmpty { "No result found." }
        }
        fake.adb.on("^content insert --uri content://com.android.contacts/raw_contacts ") { contacts.add(40 + contacts.size); "" }
        fake.adb.on("^content delete --uri 'content://com.android.contacts/raw_contacts\\?caller_is_syncadapter=true'") { contacts.clear(); "" }

        val code = fake.cli("rehearse", "--device", "emulator-5554", "--tasks", "inject-11")

        assertEquals(listOf("rehearse=inject-11 result=pass reason=ok"), fake.output.lines().filter { it.startsWith("rehearse=") })
        assertEquals(Cli.EXIT_PASSED, code)
        assertEquals(emptyList<Int>(), contacts)
    }

    @Test
    fun theBrightnessRehearsalsCompareTheLevelOnlyWhenTheOwnerSetItByHand() {
        var night = "no"
        fake.adb.on("^cmd uimode night$") { "Night mode: $night" }
        fake.adb.on("^cmd uimode night (yes|no|auto)$") { night = it.groupValues[1]; "" }
        // With automatic brightness on, the device sets the level itself, as One UI does on the A71.
        fake.adb.on("^settings put system screen_brightness_mode (\\d)$") {
            fake.adb.settings["system/screen_brightness_mode"] = it.groupValues[1]
            if (it.groupValues[1] == "1") fake.adb.settings["system/screen_brightness"] = "64"
            ""
        }
        fake.adb.settings["system/screen_brightness_mode"] = "1"
        fake.adb.settings["system/screen_brightness"] = "70"

        assertEquals(Cli.EXIT_PASSED, fake.cli("rehearse", "--device", "emulator-5554", "--tasks", "brightness,two-settings"))
        assertEquals("1", fake.adb.settings["system/screen_brightness_mode"])

        fake.adb.settings["system/screen_brightness_mode"] = "0"
        fake.adb.settings["system/screen_brightness"] = "70"
        // A manual level that the restore does not put back is a change.
        fake.adb.on("^settings put system screen_brightness 70$") { "" }
        assertEquals(Cli.EXIT_FAILED, fake.cli("rehearse", "--device", "emulator-5554", "--tasks", "brightness"))

        assertEquals(
            listOf(
                "rehearse=brightness result=pass reason=ok", "rehearse=two-settings result=pass reason=ok", "rehearse=brightness result=fail reason=changed",
            ),
            fake.output.lines().filter { it.startsWith("rehearse=") },
        )
        assertTrue(fake.output.lines().any { it.endsWith("not as before: screen_brightness") })
    }

    @Test
    fun fontSizesRehearsalPassesWhenAndroidWritesTheSetupsFontScaleBackLate() {
        // As on the A71 at 20:14: Android's handler applies the setup's 1.0 and writes it back only after the next write.
        fake.adb.settings["system/font_scale"] = "1.1"
        fake.adb.busyHandler = true

        val code = fake.cli("rehearse", "--device", "emulator-5554", "--tasks", "font-size")

        assertEquals(listOf("rehearse=font-size result=pass reason=ok"), fake.output.lines().filter { it.startsWith("rehearse=") })
        assertEquals(Cli.EXIT_PASSED, code)
        assertEquals("1.1", fake.adb.settings["system/font_scale"])
        assertEquals("1.1", fake.adb.fontScale)
    }

    @Test
    fun dndsRehearsalStopsAtItsSetupWhenTheOwnersScheduleCouldStartDuringIt() {
        fake.adb.settings["global/zen_mode"] = "0"
        fake.adb.on("^dumpsys notification --zen$") { ZenDump.android13(null, ZenDump.rule13("EVERY_NIGHT_DEFAULT_RULE", null)) }
        ZenDump.clock(fake.adb, 21, 45)

        val code = fake.cli("rehearse", "--device", "emulator-5554", "--tasks", "dnd")

        assertEquals(listOf("rehearse=dnd result=skipped reason=untestable"), fake.output.lines().filter { it.startsWith("rehearse=") })
        assertTrue(fake.output.lines().any { it.startsWith("Rehearsal of dnd: not testable on kestrel-api37: ") && it.contains("schedule") })
        assertEquals(Cli.EXIT_PASSED, code)
        // Neither the rehearsal's set_dnd priority nor its undo, set_dnd off, which would snooze the schedule.
        assertTrue(fake.adb.commands.none { it.startsWith("cmd notification set_dnd") })
    }

    @Test
    fun aChangeOfTheEmulatorsGoogleAccountsStopsTheCommandWithAnError() {
        fake.adb.hook("VOICE") { fake.accounts = 0; "sent" }

        assertEquals(Cli.EXIT_FAILED, fake.cli("run", "--device", "emulator-5554", "--task", "stand-in-hotspot", "--runs", "1"))
        assertTrue(fake.output.contains("ERROR: kestrel-api37's owner data changed during the command: Google accounts 1 before, 0 now."))
    }

    @Test
    fun aChangeOfTheEmulatorsGoogleAccountsAfterTheCommandFailedKeepsTheFirstErrorAndReportsBoth() {
        fake.adb.hook("STATE") { fake.accounts = 0; "screen=off testMode=on numbers=0" }

        assertEquals(Cli.EXIT_FAILED, fake.cli("run", "--device", "emulator-5554", "--task", "stand-in-hotspot", "--runs", "1"))

        assertTrue(fake.output.contains("REFUSED: Kestrel's accessibility service is off on emulator-5554."))
        assertTrue(fake.output.contains("ERROR: kestrel-api37's owner data changed during the command: Google accounts 1 before, 0 now."))
    }

    @Test
    fun aMalformedLocalConfigurationIsARefusalThatQuotesNoneOfIt() {
        val config = fake.root.resolve("corpus").resolve("owner").resolve("a71-config.json")
        Files.createDirectories(config.parent)
        Files.writeString(config, "{ \"gmailAddress\": \"owner@example.com\", }")

        assertEquals(Cli.EXIT_REFUSED, fake.cli("run", "--device", "emulator-5554", "--task", "stand-in-hotspot", "--runs", "1"))

        assertTrue(fake.output.contains("REFUSED: corpus\\owner\\a71-config.json is not valid JSON"))
        assertFalse(fake.output.contains("owner@example.com"))
        assertTrue(fake.adb.commands.isEmpty())
    }

    @Test
    fun aCommandThatCtrlCEndsIsNoErrorAndItsShutdownHookWritesTheRunsLeft() {
        var hook: Thread? = null
        val environment = fake.environment.let { Environment(it.root, it.adb, it.processes, it.clock, it.out, { thread -> hook = thread }) }
        // Ctrl+C while run 1's task is being given: the JVM runs the shutdown hook, here on this thread.
        fake.adb.hook("VOICE") { hook?.run(); "sent" }

        assertEquals(Cli.EXIT_FAILED, Cli(environment).run(listOf("run", "--device", "emulator-5554", "--task", "stand-in-hotspot", "--runs", "2")))

        assertEquals(listOf("result=fail", "result=fail"), runLines().map { it.split(' ')[2] })
        assertTrue(runLines().all { it.endsWith(" reason=stopped") })
        assertFalse(fake.output.contains("ERROR:"))
        assertFalse(fake.output.contains("Exception"))
    }

    @Test
    fun aRehearsalThatCtrlCEndsRehearsesNoFurtherTask() {
        var hook: Thread? = null
        val environment = fake.environment.let { Environment(it.root, it.adb, it.processes, it.clock, it.out, { thread -> hook = thread }) }
        // Ctrl+C during stand-in-hotspot's check: the JVM runs the shutdown hook, here on this thread.
        fake.adb.hook("SCREEN_HAS") { hook?.run(); "count=1 app=dev.kestrel.testapp" }

        assertEquals(Cli.EXIT_REFUSED, Cli(environment).run(listOf("rehearse", "--device", "emulator-5554", "--tasks", "stand-in-hotspot,inject-06")))

        assertFalse(fake.output.contains("rehearse=inject-06"))
        assertFalse(fake.output.contains("Rehearsal of inject-06"))
    }

    @Test
    fun listShowsEveryTaskOrThoseOfADevice() {
        assertEquals(Cli.EXIT_PASSED, fake.cli("list"))
        assertEquals(60, fake.output.lines().count { it.startsWith("task=") })
        assertTrue(fake.output.contains("task=hotspot kind=d3 devices=kestrel-api37 dryRun=stand-in-hotspot.json_on_stand-in-hotspot rehearsal=setup-only"))

        fake.devices["R58N66RVD7Z"] = FakeAdb(serial = "R58N66RVD7Z", model = "SM-A715F", avd = "", sdk = 33)
        val before = fake.output.lines().size
        assertEquals(Cli.EXIT_PASSED, fake.cli("list", "--device", "R58N66RVD7Z"))
        val names = fake.output.lines().drop(before - 1).filter { it.startsWith("task=") }.map { it.substringAfter("task=").substringBefore(' ') }
        val suite = listOf(
            "dark-mode", "brightness", "screen-timeout", "dnd", "bluetooth-off", "font-size", "wifi-settings", "battery-level", "two-settings", "alarm", "timer",
            "calendar-add", "calendar-read", "contact-add", "contact-number", "contact-delete-declined", "email-self", "notification-read",
            "firefox-url", "youtube-search", "maps-place", "photo-latest",
        )
        assertEquals(
            suite + listOf("telegram-late", "waze-home", "firefox-wiki", "telegram-photos", "read-email", "followup-settings", "quiet-time-ends", "typed-contact-then-message"),
            names,
        )
    }

    @Test
    fun aLiveSeriesGrantsTheEvaluationsPermissionsFirstAndStopsBeforeAnyRunWhenOneIsMissing() {
        fake.adb.refusedGrants += "android.permission.READ_CALL_LOG"

        assertEquals(Cli.EXIT_REFUSED, fake.cli("suite", "--device", "emulator-5554", "--live", "--runs", "1", "--tasks", "hotspot"))

        assertTrue(fake.output, fake.output.contains("android.permission.READ_CALL_LOG"))
        assertTrue(fake.adb.hookCalls("VOICE").isEmpty())
        assertEquals(EvalPermissions.SET.filter { it.kind == EvalAccess.Kind.RUNTIME_PERMISSION }.map { it.id }.toSet() - "android.permission.READ_CALL_LOG", fake.adb.granted.getValue("dev.kestrel").toSet())
        // A series without a model grants nothing.
        val dry = FakeEnvironment()
        assertEquals(Cli.EXIT_PASSED, dry.cli("suite", "--device", "emulator-5554", "--runs", "1", "--tasks", "stand-in-hotspot"))
        assertTrue(dry.adb.commands.none { it.startsWith("pm grant") || it.startsWith("cmd notification allow_listener") })
    }

    @Test
    fun aLiveSeriesRecordsTheKeptSetWithKestrelsNotificationAccessInSuiteJson() {
        fake.cli("suite", "--device", "emulator-5554", "--live", "--runs", "1", "--tasks", "hotspot")

        val call = SuiteCall.read(fake.root.resolve("eval-results").resolve("kestrel-api37-20261123-120000"))!!
        assertEquals(EvalAccess.entries.associate { it.id to true }, call.permissions)
        assertEquals(EvalAccess.NOTIFICATION_LISTENER.id, fake.adb.settings["secure/enabled_notification_listeners"])
        assertTrue(fake.adb.commands.none { it.startsWith("pm revoke dev.kestrel ") || it.startsWith("cmd notification disallow_listener") })
    }
}
