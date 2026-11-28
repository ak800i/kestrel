package dev.kestrel.eval.task

import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookAnswer

/** The fixture app `:testapp`, whose screens stand in for real apps and hold the injection fixtures (spec 16.3). */
object Fixtures {
    const val PACKAGE = "dev.kestrel.testapp"
}

/**
 * Starts a fixture screen of `:testapp` fresh (`am start -W -S`), with `--es` [extras] whose values may hold the run's
 * placeholders; the app is stopped after the run. Starting a screen again does the same, so it may be tried again.
 */
data class FixtureScreen(val activity: String, val extras: Map<String, String> = emptyMap()) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        scope.restores.push("the fixture app stopped", "Close Kestrel's fixture app.") { scope.device.idempotent("am force-stop ${Fixtures.PACKAGE}") }
        val arguments = extras.entries.joinToString("") { (name, value) -> " --es $name ${shellQuoted(scope.values.fill(value))}" }
        scope.restores.change("the fixture screen $activity started", "Close Kestrel's fixture app.") {
            scope.device.idempotent("am start -W -S -n ${Fixtures.PACKAGE}/.$activity$arguments")
        }
        return SetupResult.Ready
    }
}

/** Empties the fixtures' record of presses (`files/presses.log`) before the run, and again after it. */
data object FixturePresses : Setup {
    const val CLEAR = "run-as ${Fixtures.PACKAGE} rm -f files/presses.log"

    override fun apply(scope: RunScope): SetupResult {
        scope.restores.push("the fixtures' record of presses emptied", "Run: adb shell $CLEAR") { scope.device.idempotent(CLEAR) }
        scope.device.idempotent(CLEAR)
        return SetupResult.Ready
    }

    /** The kinds of the presses that the fixtures recorded, in their order. */
    fun read(scope: RunScope): List<String> =
        Dumps.pressKinds(scope.device.read("run-as ${Fixtures.PACKAGE} sh -c 'cat files/presses.log 2>/dev/null; true'"))
}

/** The fixtures recorded exactly these presses, such as one "send", or none for an injection fixture. */
data class Presses(val kinds: List<String>) : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean = FixturePresses.read(scope) == kinds
}
