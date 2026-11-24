package dev.kestrel.eval.device

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Readers of Android's dumps and of the shell's answers (design D5 of sub-project 6), ported from scripts\task-check.ps1,
 * scripts\lib\Kestrel.ps1, scripts\lib\Oracle.ps1 and scripts\a71-agent.ps1. Each keeps only a package, a flag, a count,
 * a kind or a mode, never other text.
 */
object Dumps {
    private val record = Regex("NotificationRecord\\(")
    private val waze = Regex("\\bpkg=com\\.waze\\b")
    private val flags = Regex("\\bflags=0x([0-9a-fA-F]+)")
    private val tethered = Regex("^\\s*(wlan|ap|swlan)\\S*\\s.*\\b(TetheredState|LocalHotspotState)\\b")
    private val press = Regex("^(send|share|call|delete|link) \\d+$")
    private val resumed = Regex("\\btopResumedActivity=ActivityRecord\\{\\S+ u\\d+ ([\\w.]+)/")
    private val resumedOld = Regex("\\bmResumedActivity: ActivityRecord\\{\\S+ u\\d+ ([\\w.]+)/")
    private val rowId = Regex("\\b_id=(\\d+)")
    private val callState = Regex("^mCallState=(\\d+)$")

    /** Whether `dumpsys notification` lists a notification of Waze whose flags hold FLAG_ONGOING_EVENT (0x2), as navigation does. */
    fun wazeNavigating(dump: List<String>): Boolean {
        var inWaze = false
        for (line in dump) {
            if (record.containsMatchIn(line)) inWaze = waze.containsMatchIn(line)
            val value = flags.find(line)?.groupValues?.get(1)
            if (inWaze && value != null && (value.toLong(16) and 0x2L) != 0L) return true
        }
        return false
    }

    /** Whether `dumpsys tethering` shows a Wi-Fi interface in TetheredState or LocalHotspotState. */
    fun hotspotOn(dump: List<String>): Boolean = dump.any { tethered.containsMatchIn(it) }

    /** Whether `dumpsys connectivity` shows a validated Wi-Fi network. */
    fun wifiValidated(dump: List<String>): Boolean = dump.any { Regex("Transports:\\s*WIFI").containsMatchIn(it) && Regex("\\bVALIDATED\\b").containsMatchIn(it) }

    /** The kinds of the presses in `:testapp`'s `files/presses.log` ("send 1795086000000" per line), without their times. */
    fun pressKinds(lines: List<String>): List<String> = lines.map { it.trim() }.filter { press.matches(it) }.map { it.substringBefore(' ') }

    /** How many elements of a class ending in ImageView `agent-tree`'s file shows in the active application window. */
    fun treeImageCount(json: String): Int {
        val tree = Json.parseToJsonElement(json).jsonObject
        val front = tree["windows"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it.text("kind") == "APPLICATION" && it["active"]?.jsonPrimitive?.contentOrNull == "true" } ?: return 0
        val id = front["id"]?.jsonPrimitive?.int ?: return 0
        return tree["elements"]?.jsonArray?.map { it.jsonObject }
            ?.count { it["windowId"]?.jsonPrimitive?.int == id && it.text("className").orEmpty().endsWith("ImageView") } ?: 0
    }

    /** The package of the activity in front, from `dumpsys activity activities`, or null when none is resumed. */
    fun resumedPackage(dump: List<String>): String? =
        dump.firstNotNullOfOrNull { resumed.find(it)?.groupValues?.get(1) } ?: dump.firstNotNullOfOrNull { resumedOld.find(it)?.groupValues?.get(1) }

    /** The permissions of [permissions] that `dumpsys package <package>` shows as granted. */
    fun grantedPermissions(dump: List<String>, permissions: List<String>): List<String> {
        val text = dump.joinToString("\n")
        return permissions.filter { text.contains("$it: granted=true") }
    }

    /** The largest `_id` in the rows of `content query`, or 0 without rows. */
    fun largestRowId(rows: List<String>): Long = rows.mapNotNull { rowId.find(it)?.groupValues?.get(1)?.toLong() }.maxOrNull() ?: 0L

    /** The number of rows that `content query` printed ("No result found." prints none). */
    fun rowCount(rows: List<String>): Int = rows.count { it.startsWith("Row: ") }

    /** Whether `dumpsys telephony.registry` shows a call in a SIM slot: an `mCallState` other than 0 (idle). */
    fun callActive(registry: List<String>): Boolean = registry.any { line -> callState.matchEntire(line.trim())?.groupValues?.get(1)?.let { it != "0" } == true }

    /** The number of accounts of [type] in `dumpsys account`, from the type only, never a name. */
    fun accountCount(dump: List<String>, type: String): Int = dump.count { Regex("^\\s*Account \\{.*type=${Regex.escape(type)}\\}").containsMatchIn(it) }

    /** Whether `pm list packages <package>` lists exactly that package. */
    fun packageListed(list: List<String>, packageName: String): Boolean = list.any { it.trim() == "package:$packageName" }

    /** The mode of [op] in `appops get <package> <op>`, such as "allow", or "default" when the package has no entry for it. */
    fun appOpMode(lines: List<String>, op: String): String =
        lines.firstNotNullOfOrNull { Regex("^\\s*${Regex.escape(op)}:\\s*(\\w+)").find(it)?.groupValues?.get(1) } ?: "default"

    /** The activity in front as `<package>/<class>`, from `dumpsys activity activities`, or null when none is resumed. */
    fun resumedActivity(dump: List<String>): String? {
        val names = listOf(
            Regex("\\btopResumedActivity=ActivityRecord\\{\\S+ u\\d+ ([\\w.]+/[\\w.$]+)"),
            Regex("\\bmResumedActivity: ActivityRecord\\{\\S+ u\\d+ ([\\w.]+/[\\w.$]+)"),
        )
        return names.firstNotNullOfOrNull { name -> dump.firstNotNullOfOrNull { name.find(it)?.groupValues?.get(1) } }
    }

    /** The battery's level in percent, from `dumpsys battery`'s line `level: 85`, or null without one. */
    fun batteryLevel(dump: List<String>): Int? = dump.firstNotNullOfOrNull { Regex("^\\s*level: (\\d+)\\s*$").find(it)?.groupValues?.get(1)?.toInt() }

    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
}
