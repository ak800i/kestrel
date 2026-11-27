package dev.kestrel.eval.device

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The two test devices (design D1 of sub-project 6), with the name that the results use and the results folder's prefix. */
enum class DeviceKind(val label: String, val folder: String) {
    EMULATOR("kestrel-api37", "kestrel-api37"),
    A71("the A71", "a71"),
}

/** A device that [DeviceGuard] accepted, with its Android API level. */
data class TestDevice(val serial: String, val kind: DeviceKind, val sdk: Int)

/** A refusal before any run (exit code 2, design D2 of sub-project 6): a device, the live gate or a missing configuration. */
class Refusal(message: String) : Exception(message)

/**
 * Accepts only kestrel-api37, when `ro.boot.qemu.avd_name` and the console's `emu avd name` both say so, and the Galaxy
 * A71, by its serial and its model (design D3 of sub-project 6). The Galaxy S23+ is the owner's daily phone: it is
 * refused with its own message, and no option allows it. Every other device is refused too. A busy emulator sometimes
 * answers a query with nothing, so a refusal is checked twice more, 2 s apart, as Assert-TestDevice does.
 */
class DeviceGuard(private val clock: Clock = SystemClock) {
    fun accept(adb: Adb): TestDevice {
        var state = ""
        var model = ""
        var avd = ""
        repeat(TRIES) { attempt ->
            if (attempt > 0) clock.sleep(RETRY)
            state = answer { adb.host(listOf("get-state"), QUICK) }
            if (state != "device") return@repeat
            model = answer { adb.shell("getprop ro.product.model", QUICK) }
            avd = answer { adb.shell("getprop ro.boot.qemu.avd_name", QUICK) }
            if (model.startsWith(DAILY_PHONE)) {
                throw Refusal("Refusing ${adb.serial}: it is the owner's Galaxy S23+ ($model), which :tools:eval never uses.")
            }
            val kind = when {
                avd == EMULATOR_AVD && consoleName(adb) == EMULATOR_AVD -> DeviceKind.EMULATOR
                adb.serial == A71_SERIAL && model.startsWith(A71_MODEL) -> DeviceKind.A71
                else -> null
            }
            if (kind != null) {
                val sdk = answer { adb.shell("getprop ro.build.version.sdk", QUICK) }.toIntOrNull()
                    ?: throw Refusal("${adb.serial} did not say its Android version.")
                return TestDevice(adb.serial, kind, sdk)
            }
        }
        if (state != "device") throw Refusal("Device ${adb.serial} is not connected.")
        throw Refusal("Refusing ${adb.serial} (model '$model', AVD '$avd'). :tools:eval runs only on kestrel-api37 and the Galaxy A71.")
    }

    private fun answer(call: () -> ShellResult): String = try {
        call().out.trim()
    } catch (e: AdbException) {
        ""
    }

    companion object {
        const val EMULATOR_AVD = "kestrel-api37"
        const val A71_SERIAL = "R58N66RVD7Z"
        const val A71_MODEL = "SM-A715F"
        const val DAILY_PHONE = "SM-S916"
        private const val TRIES = 3
        private val RETRY = 2.seconds
        internal val QUICK = 30.seconds

        /** The AVD name that the emulator's console gives, from the first line of `emu avd name`, or "" without one within [timeout]. */
        fun consoleName(adb: Adb, timeout: Duration = QUICK): String = try {
            adb.host(listOf("emu", "avd", "name"), timeout).out.lineSequence().firstOrNull()?.trim().orEmpty()
        } catch (e: AdbException) {
            ""
        }
    }
}
