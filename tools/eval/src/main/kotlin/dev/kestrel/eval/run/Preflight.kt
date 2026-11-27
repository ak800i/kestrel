package dev.kestrel.eval.run

import dev.kestrel.eval.device.Clock
import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.hook.HookError
import dev.kestrel.eval.hook.HookLost
import dev.kestrel.eval.hook.HookSilent
import dev.kestrel.eval.hook.Hooks
import dev.kestrel.eval.task.Fixtures
import kotlin.time.Duration.Companion.seconds

/**
 * What every run needs, checked before the first run (design D5 and D7 of sub-project 6): Kestrel's debug build and, for
 * the fixtures, `:testapp` installed (through [Installer] when missing; `:testapp` leaves the A71 again when the tool
 * installed it there, as install.ps1 says the session that installs it must), Kestrel started so that it gets the hooks'
 * broadcasts, its accessibility service on, test mode on (turned on with a restore when it is off, so that the work goes on
 * without the owner) and Kestrel listening. A problem is a [Refusal], before any run.
 */
class Preflight(
    private val device: Device,
    private val hooks: Hooks,
    private val restores: Restores,
    private val clock: Clock,
    private val installer: Installer,
    private val conversations: ConversationGuard,
) {
    fun prepare(needsTestApp: Boolean) {
        if (!installed(KESTREL)) installer.install(device.serial, testApp = false)
        if (needsTestApp && !installed(Fixtures.PACKAGE)) {
            if (device.kind == DeviceKind.A71) {
                restores.push(":testapp removed from the A71", "Run: adb -s ${device.serial} uninstall ${Fixtures.PACKAGE}") {
                    if (installed(Fixtures.PACKAGE)) device.once("pm uninstall ${Fixtures.PACKAGE}")
                    check(!installed(Fixtures.PACKAGE)) { ":testapp is still installed on the A71." }
                }
            }
            installer.install(device.serial, testApp = true)
        }
        wake()
        if (hooks.state()["screen"] != "on") throw Refusal("Kestrel's accessibility service is off on ${device.serial}. Turn it on first.")
        conversationHooks()
        testModeOn()
        try {
            waitListening()
        } catch (e: IllegalStateException) {
            throw Refusal(e.message.orEmpty())
        }
    }

    /** A stopped app gets no broadcast, so Kestrel's activity starts first, and the home screen comes back after it. */
    fun wake() {
        if (!installed(KESTREL)) return
        device.idempotent("am start -n $KESTREL/.app.MainActivity")
        clock.sleep(3.seconds)
        device.idempotent("input keyevent KEYCODE_HOME")
    }

    /**
     * Waits up to 90 s until Kestrel listens: before the first run, and before each run, because Kestrel's process may
     * have ended since the run before, as when it crashed; Android then binds its accessibility service again and Kestrel
     * goes back to its stored listening mode by itself. No step or restore takes back one of Kestrel's permissions or its
     * notification access, which would end its process too: they are the evaluation's kept set ([EvalPermissions]).
     */
    fun waitListening() {
        val deadline = clock.elapsed() + LISTENING
        while (true) {
            val mode = try {
                hooks.voiceState()["mode"]
            } catch (e: HookSilent) {
                null
            } catch (e: HookError) {
                null
            }
            if (mode == "listening") return
            check(clock.elapsed() < deadline) { "Kestrel is not listening on ${device.serial}. Turn on its accessibility service and listening first." }
            clock.sleep(3.seconds)
        }
    }

    private fun testModeOn() {
        if (hooks.state()["testMode"] == "on") return
        restores.push("test mode off again on ${device.serial}", "Run: scripts\\hook.ps1 -Serial ${device.serial} test-mode -On false") {
            try {
                hooks.testMode(false)
            } catch (e: HookLost) {
                check(hooks.state()["testMode"] == "off") { "test mode is still on" }
            }
        }
        try {
            hooks.testMode(true)
        } catch (e: HookLost) {
            // Read back below.
        }
        if (hooks.state()["testMode"] != "on") throw Refusal("Test mode did not turn on on ${device.serial}.")
    }

    private fun installed(packageName: String): Boolean = Dumps.packageListed(device.read("pm list packages $packageName"), packageName)

    // Every run closes Kestrel's open conversation first, stops a goal that it left open and deletes the hooks' own
    // conversations after it (ConversationGuard, design 14.1 of sub-project 7), which a build from before plan 7D cannot
    // answer, nor one without plan 7F's delete-typed. delete-typed here also deletes what an earlier series left, and the
    // series' cleanup, a restore with the series' guard, deletes what this one's runs leave, and only that.
    private fun conversationHooks() {
        val open = try {
            hooks.conversationState()["open"]
        } catch (e: HookError) {
            null
        }
        if (open == null) refuse("conversation-state", "plan 7D of sub-project 7")
        val deleted = try {
            hooks.deleteTyped()["deleted"]
        } catch (e: HookError) {
            null
        }
        if (deleted == null) refuse("conversation-control do=delete-typed", "plan 7F of sub-project 7")
        restores.push("the hooks' own conversations deleted", "Kestrel > Past conversations: delete the conversations of the series' runs.") {
            conversations.deleteLeft()
        }
    }

    private fun refuse(hook: String, plan: String): Nothing = throw Refusal(
        "Kestrel on ${device.serial} answers no $hook, as a build from before $plan does; install the current debug build with scripts\\install.ps1.",
    )

    private companion object {
        const val KESTREL = "dev.kestrel"
        val LISTENING = 90.seconds
    }
}
