package dev.kestrel.eval.egress

import dev.kestrel.eval.device.Clock
import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceChanged
import dev.kestrel.eval.device.DeviceCommandFailed
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.hook.HookLost
import dev.kestrel.eval.hook.Hooks
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * PCAPdroid's one-time setup on a test device, which needs no owner (design D11 of sub-project 6): Private DNS off or
 * automatic; the pinned APK ([apk]) installed; its notification permission, the VPN op and on API 37 the local network
 * permission; its first start after a force-stop, whose pages the script's own taps pass ("Next", "Skip", "DONE", "Done",
 * "OK", "Continue", each counted before its tap, at most 10 pages); a new API key by the script's own taps, each on the one
 * element that shows with its label, read once from PCAPdroid's own node
 * of `uiautomator dump`, every other node dropped, into [keys]; and a capture started and stopped with it. A device that
 * has PCAPdroid and a working key is only checked. A step that screen-tap cannot press stops the setup there. PCAPdroid
 * stays installed, with its key (the owner's answer 4), and the home screen is left in front.
 */
class EgressSetup(
    private val device: Device,
    private val hooks: Hooks,
    private val clock: Clock,
    private val keys: PcapdroidKeys,
    private val apk: () -> Path,
) {
    /** "checked" for a device that was ready, "set-up" for one that this run set up; either way, the home screen is left in front. */
    fun run(): String {
        val mode = device.read("settings get global private_dns_mode").joinToString("").trim()
        if (mode !in PRIVATE_DNS) throw Refusal("Private DNS is '$mode' on ${device.serial}: set it to off or automatic, because PCAPdroid cannot read the names of a private DNS server.")
        val known = keys.get(device.serial)
        val result = if (installed() && known != null && check(known)) "checked" else setUp()
        // The capture's control brings PCAPdroid's task to the front.
        device.idempotent("input keyevent KEYCODE_HOME")
        return result
    }

    private fun setUp(): String {
        if (!installed()) {
            device.install(apk())
            if (!installed()) throw IllegalStateException("PCAPdroid did not install on ${device.serial}.")
        }
        grant()
        firstStart()
        val key = generateKey()
        keys.put(device.serial, key)
        if (!check(key)) throw IllegalStateException("PCAPdroid did not start and stop a capture with its new key on ${device.serial}.")
        return "set-up"
    }

    private fun installed(): Boolean = Dumps.packageListed(device.read("pm list packages ${Pcapdroid.PACKAGE}"), Pcapdroid.PACKAGE)

    private fun grant() {
        if (device.test.sdk >= 33) device.idempotent("pm grant ${Pcapdroid.PACKAGE} android.permission.POST_NOTIFICATIONS")
        device.idempotent("appops set ${Pcapdroid.PACKAGE} ACTIVATE_VPN allow")
        if (device.test.sdk >= 37) {
            try {
                device.idempotent("pm grant ${Pcapdroid.PACKAGE} android.permission.ACCESS_LOCAL_NETWORK")
            } catch (e: DeviceCommandFailed) {
                // The first start's prompt is then answered by the same taps as its pages.
            }
        }
    }

    private fun firstStart() {
        // A launcher intent brings back PCAPdroid's task as an earlier set-up or a failed one left it, so the key steps would
        // not start on its main screen.
        device.idempotent("am force-stop ${Pcapdroid.PACKAGE}")
        device.idempotent("monkey -p ${Pcapdroid.PACKAGE} -c android.intent.category.LAUNCHER 1")
        clock.sleep(3.seconds)
        repeat(MAX_TAPS) { if (!nextPage()) return started() }
        started()
    }

    /**
     * Taps the first label of [ONBOARDING] that screen-has counts and screen-tap presses, and false when none is counted.
     * screen-has compares labels without letter case and screen-tap exactly, so "DONE" is counted on a "Done" button too.
     */
    private fun nextPage(): Boolean {
        var counted = false
        for (label in ONBOARDING) {
            if (hooks.screenHas(label = label).int("count") < 1) continue
            counted = true
            if (pressed(label, null) == null) return true
        }
        if (!counted) return false
        throw IllegalStateException("PCAPdroid's first start shows a page that screen-tap cannot press on ${device.serial}: finish it there by hand once, and run egress-setup again.")
    }

    private fun started() {
        val front = hooks.screenHas()["app"]
        if (front != Pcapdroid.PACKAGE) {
            throw IllegalStateException("PCAPdroid's first start stopped at $front on ${device.serial}: finish it there by hand once, and run egress-setup again.")
        }
    }

    /** Settings > Control permissions > its menu > "Generate API key", by the script's own taps; a step that does not show is looked for 3 scrolls further. */
    private fun generateKey(): String {
        for (step in KEY_STEPS) {
            var shown = step.firstOrNull { visible(it) }
            var scrolls = 0
            while (shown == null && scrolls < 3) {
                device.once("input swipe 540 1600 540 600 300")
                clock.sleep(1.seconds)
                scrolls++
                shown = step.firstOrNull { visible(it) }
            }
            tap(shown ?: throw IllegalStateException("PCAPdroid showed none of ${step.joinToString()} on ${device.serial}."))
        }
        val key = readKey()
        CLOSE.firstOrNull { visible(it) }?.let { tap(it) }
        return key
    }

    /** The one 32-character key in a node of PCAPdroid's package; every other node is dropped unread, and nothing of the dump is kept. */
    private fun readKey(): String {
        val dump = device.execOut(listOf("uiautomator", "dump", "/dev/tty"))
        val found = NODE.findAll(dump).mapNotNull { node ->
            if (PACKAGE_OF.find(node.value)?.groupValues?.get(1) != Pcapdroid.PACKAGE) return@mapNotNull null
            TEXT_OF.find(node.value)?.groupValues?.get(1)?.takeIf { KEY.matches(it) }
        }.toList()
        return found.singleOrNull() ?: throw IllegalStateException("PCAPdroid's API key did not show once on ${device.serial} (${found.size} found).")
    }

    /**
     * Whether PCAPdroid shows exactly one element that screen-tap would press for [label]: one clickable element, or none and
     * one element at all, such as a preference's title, whose row is named after its title and summary and takes the tap.
     * A label that shows more than once is never tapped.
     */
    private fun visible(label: String): Boolean {
        val clickable = hooks.screenHas(packageName = Pcapdroid.PACKAGE, label = label, state = "clickable").int("count")
        val count = if (clickable == 0) hooks.screenHas(packageName = Pcapdroid.PACKAGE, label = label).int("count") else clickable
        if (count > 1) throw IllegalStateException("PCAPdroid shows $label $count times on ${device.serial}, so the script does not tap it.")
        return count == 1
    }

    /** A key step's tap in PCAPdroid; a step that screen-tap cannot press stops the set-up there. */
    private fun tap(label: String) {
        val refused = pressed(label, Pcapdroid.PACKAGE) ?: return
        throw IllegalStateException("screen-tap could not press $label in PCAPdroid on ${device.serial} ($refused).")
    }

    /** The script's own tap: null when it pressed [label], else screen-tap's answer. A lost answer counts as a tap, because the next count reads the screen back. */
    private fun pressed(label: String, packageName: String?): HookAnswer? {
        val answer = try {
            hooks.screenTap(label = label, packageName = packageName)
        } catch (e: HookLost) {
            null
        }
        if (answer != null && answer["tapped"] != "true") return answer
        clock.sleep(2.seconds)
        return null
    }

    /** A capture of Kestrel's traffic started and stopped with [key], without a file. */
    private fun check(key: String): Boolean {
        try {
            control(device, key, "start", " -e pcap_dump_mode none -e app_filter dev.kestrel")
            if (!waitFor(true)) return false
        } finally {
            control(device, key, "stop")
        }
        return waitFor(false)
    }

    private fun waitFor(state: Boolean): Boolean {
        repeat(10) {
            if (running(device) == state) return true
            clock.sleep(1.seconds)
        }
        return false
    }

    companion object {
        private val PRIVATE_DNS = setOf("null", "off", "opportunistic")
        private val ONBOARDING = listOf("Next", "Skip", "DONE", "Done", "OK", "Continue")

        // PCAPdroid 2.0.4 shows Settings as a button of its main screen's bar, not in its menu.
        private val KEY_STEPS = listOf(listOf("Settings"), listOf("Control permissions"), listOf("More options"), listOf("Generate API key"))
        private val CLOSE = listOf("OK", "Close")
        private const val MAX_TAPS = 10
        private val NODE = Regex("<node\\b[^>]*>")
        private val PACKAGE_OF = Regex("\\bpackage=\"([^\"]*)\"")
        private val TEXT_OF = Regex("\\btext=\"([^\"]*)\"")
        private val KEY = Regex("^[A-Za-z0-9]{32}$")

        /** Whether PCAPdroid's capture service runs, from `dumpsys activity services`, because its status cannot answer adb. */
        fun running(device: Device): Boolean =
            device.read("dumpsys activity services ${Pcapdroid.PACKAGE}").any { it.contains("ServiceRecord{") && it.contains("CaptureService") }

        /** PCAPdroid's control with its API key (`CaptureCtrl`); an error never names the command, which holds the key. */
        fun control(device: Device, key: String, action: String, extras: String = "") {
            try {
                // A lost answer is read back by the capture service's state.
                device.once("am start -n ${Pcapdroid.CONTROL} -e action $action -e api_key $key$extras")
            } catch (e: DeviceChanged) {
                throw DeviceChanged("${device.serial} changed while PCAPdroid's control ran.")
            }
        }
    }
}
