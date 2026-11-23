package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceCommandFailed
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.hook.HookError
import dev.kestrel.eval.hook.HookSilent
import kotlin.time.Duration.Companion.seconds

/**
 * The hotspot off before the run, as task-check.ps1's hotspot-setup did: a device whose hotspot the tool cannot read or
 * turn off cannot run the task, and on the emulator a Settings page without the Wi-Fi hotspot's entry makes the task "not
 * testable here". The setup only turns the hotspot off, the state that the run's end puts back, so its restore is pushed
 * once the hotspot is off and before the task can turn it on: it turns the hotspot off again and waits for a validated
 * Wi-Fi network, as task-check.ps1's hotspot-off did.
 */
data object HotspotOff : Setup {
    override fun apply(scope: RunScope): SetupResult {
        if (!Hotspots.stop(scope)) return SetupResult.Untestable("its hotspot's state cannot be read, or the hotspot cannot be turned off")
        Hotspots.waitWifiValidated(scope)
        if (scope.device.kind == DeviceKind.EMULATOR) {
            scope.device.idempotent("am start -W -a android.settings.TETHER_SETTINGS")
            scope.clock.sleep(2.seconds)
            val entries = scope.hooks.screenHas(packageName = Hotspots.SETTINGS, contains = "Wi-Fi hotspot").int("count")
            scope.device.idempotent("am force-stop ${Hotspots.SETTINGS}")
            if (entries < 1) return SetupResult.Untestable("Settings shows no Wi-Fi hotspot here")
        }
        scope.restores.push("the hotspot off", "Settings > Network > Hotspot and tethering: turn the hotspot off, and Wi-Fi on.") {
            check(Hotspots.stop(scope)) { "The hotspot is still on, or its state could not be read." }
            Hotspots.waitWifiValidated(scope)
        }
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf("hotspot" to (Hotspots.state(scope)?.toString() ?: "unknown"))
}

/** The hotspot is on: `dumpsys tethering` shows a Wi-Fi interface in TetheredState or LocalHotspotState. */
data object HotspotOn : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean = Dumps.hotspotOn(scope.device.read("dumpsys tethering"))
}

/** The hotspot's state and its stop (task-check.ps1's `Get-HotspotState`, `Stop-Hotspot` and `Wait-WifiValidated`). */
object Hotspots {
    const val SETTINGS = "com.android.settings"

    /** The labels of the hotspot's switch: One UI's "Mobile Hotspot", AOSP's "Wi-Fi hotspot" and "Use Wi-Fi hotspot". */
    private val SWITCHES = listOf("Mobile Hotspot", "Wi-Fi hotspot", "Use Wi-Fi hotspot", "Hotspot")
    private val BOUNDS = Regex("^(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)$")

    /** Whether the hotspot is on, or null when the device does not give `dumpsys tethering`. */
    fun state(scope: RunScope): Boolean? = try {
        Dumps.hotspotOn(scope.device.read("dumpsys tethering"))
    } catch (e: DeviceCommandFailed) {
        null
    }

    /**
     * Turns the hotspot off, only while it is on: with `cmd wifi stop-softap`, which Android 17's user build refuses to
     * the shell, and then with the switch of the hotspot's settings page, the script's own tap at its bounds. Returns
     * whether the hotspot is off; false also when its state cannot be read. A lost answer of the stop or the tap is read
     * back by the state that follows it.
     */
    fun stop(scope: RunScope): Boolean {
        val on = state(scope) ?: return false
        if (!on) return true
        scope.device.once("cmd wifi stop-softap")
        scope.clock.sleep(3.seconds)
        if (state(scope) == false) return true
        try {
            scope.device.idempotent("am start -W -a android.settings.TETHER_SETTINGS")
            scope.clock.sleep(2.seconds)
            for (label in SWITCHES) {
                val found = scope.hooks.screenHas(label = label, className = "Switch", state = "checked")
                val bounds = found["bounds"]?.let { BOUNDS.matchEntire(it) } ?: continue
                if (found.int("count") < 1) continue
                val (left, top, right, bottom) = bounds.destructured.toList().map { it.toInt() }
                scope.device.once("input tap ${(left + right) / 2} ${(top + bottom) / 2}")
                scope.clock.sleep(5.seconds)
                break
            }
        } catch (e: DeviceCommandFailed) {
            scope.console.warn("The hotspot's switch could not be tapped on ${scope.device.serial}.")
        } catch (e: HookError) {
            scope.console.warn("The hotspot's switch could not be found on ${scope.device.serial}.")
        } catch (e: HookSilent) {
            scope.console.warn("The hotspot's switch could not be found on ${scope.device.serial}.")
        } finally {
            try {
                scope.device.idempotent("am force-stop $SETTINGS")
            } catch (e: DeviceCommandFailed) {
                scope.console.warn("Settings could not be stopped on ${scope.device.serial}.")
            }
        }
        return state(scope) == false
    }

    /** Waits up to 60 s for a validated Wi-Fi network, after `svc wifi enable` when it has none. */
    fun waitWifiValidated(scope: RunScope) {
        if (Dumps.wifiValidated(scope.device.read("dumpsys connectivity"))) return
        scope.device.idempotent("svc wifi enable")
        val deadline = scope.clock.elapsed() + 60.seconds
        while (scope.clock.elapsed() < deadline) {
            if (Dumps.wifiValidated(scope.device.read("dumpsys connectivity"))) return
            scope.clock.sleep(2.seconds)
        }
        throw IllegalStateException("Wi-Fi did not come back with a validated network within 60 s.")
    }
}
