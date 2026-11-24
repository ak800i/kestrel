package dev.kestrel.eval.device

import kotlin.time.Duration

/**
 * A small device for the tests (design D3 of sub-project 6): properties, settings, files, the answers of commands and of
 * the debug hooks by action, and every command that reached it. A guarded command runs only when the guard's property
 * matches its pattern, as on a real device; [loseNext] loses the next answer of a matching command, as adb does when a
 * device goes away for a moment, and [hookCalls] gives the extras of each broadcast.
 */
class FakeAdb(
    override val serial: String = "emulator-5554",
    model: String = "sdk_gphone64_x86_64",
    avd: String = "kestrel-api37",
    sdk: Int = 37,
) : Adb {
    val props = mutableMapOf("ro.product.model" to model, "ro.boot.qemu.avd_name" to avd, "ro.build.version.sdk" to "$sdk")
    var consoleName = avd
    var state = "device"

    /** The commands inside the guard, in their order. */
    val commands = mutableListOf<String>()

    /** The guarded commands as they went to adb. */
    val raw = mutableListOf<String>()
    val hostCommands = mutableListOf<List<String>>()

    /** The output of a host command other than `get-state` and `emu avd name`, such as `exec-out uiautomator dump /dev/tty`. */
    var hostOut: (List<String>) -> String = { "" }
    val settings = mutableMapOf<String, String>()

    /**
     * The display's override density that `wm density` prints, which Android applies at once; null without one. `wm density
     * <n>|reset` sets it and its stored key `secure/display_density_forced`, as Android does, while `settings put` changes
     * only the stored key, which Android applies only at boot.
     */
    var density: String? = null

    /**
     * The font scale of Android's configuration, which `dumpsys window windows` prints in `mGlobalConfiguration`; null while
     * it is the stored `system/font_scale` (or 1.0 without one), as on a device at rest. Android applies a write of the key
     * as ActivityTaskManagerService does, on its handler: the key's observer reads the key and, when the configuration's
     * scale differs, applies it and posts a write of the applied scale back into the key. The handler runs its messages
     * at once after each command, or, while [busyHandler], one message before each command, as on the A71 when its
     * handler is busy, so that a write-back can land after a later write and overwrite it.
     */
    var fontScale: String? = null
    var busyHandler = false
    private val handler = ArrayDeque<() -> Unit>()

    /** Each package's granted runtime permissions, which `pm grant` and `pm revoke` change and `dumpsys package` prints. */
    val granted = mutableMapOf<String, MutableSet<String>>()

    /**
     * The permissions that `pm grant` leaves ungranted with no error, as Android does with a hard-restricted permission,
     * such as READ_SMS, of an app that was installed without the installer's exemption.
     */
    val refusedGrants = mutableSetOf<String>()

    /**
     * The notification listeners that `cmd notification allow_listener` leaves disallowed with no error, as Android does
     * when it restricts an app's settings. A change of the listeners shows in the secure setting enabled_notification_listeners,
     * which lists them joined with ':', only after [listenerLag] more reads of it, as Android 17 writes it a moment later.
     */
    val refusedListeners = mutableSetOf<String>()
    var listenerLag = 0
    private var listenersShown: String? = null
    private var lagLeft = 0
    val files = mutableMapOf<String, String>()
    private val handlers = mutableListOf<Pair<Regex, (MatchResult) -> String?>>()
    private val hooks = mutableMapOf<String, (Map<String, String>) -> Pair<Int, String>>()
    private val broadcasts = mutableListOf<Pair<String, Map<String, String>>>()
    private val lost = mutableListOf<Regex>()

    /** Answers a command that matches [pattern] (the newest rule wins) with its output, or null for a failed command. */
    fun on(pattern: String, reply: (MatchResult) -> String?) {
        handlers.add(0, Regex(pattern) to reply)
    }

    /** Answers the debug hook [action] (such as AGENT_TASK) with `name=value` pairs, as result -1. */
    fun hook(action: String, answer: (Map<String, String>) -> String) {
        hooks[action] = { extras -> -1 to answer(extras) }
    }

    /** Answers the debug hook [action] with an error code and its reason. */
    fun hookFails(action: String, reason: String) {
        hooks[action] = { _ -> 1 to reason }
    }

    /** Loses the next answer of a command that matches [pattern]: the command reached the device, but adb gave no answer. */
    fun loseNext(pattern: String) {
        lost.add(Regex(pattern))
    }

    /** The extras of each broadcast of [action], in their order. */
    fun hookCalls(action: String): List<Map<String, String>> = broadcasts.filter { it.first == action }.map { it.second }

    // Synchronized, because a test's shutdown hook may run restores while the main thread still sends commands.
    @Synchronized
    override fun shell(command: String, timeout: Duration): ShellResult {
        val guard = GUARD.matchEntire(command)
        if (guard == null) {
            commands.add(command)
            return ShellResult(0, answer(command).orEmpty() + "\n", "")
        }
        raw.add(command)
        val (property, pattern, inner) = guard.destructured
        val expected = Regex(Regex.escape(pattern).replace("*", "\\E.*\\Q"))
        if (!expected.matches(props[property].orEmpty())) return ShellResult(0, "kestrel-refused\n", "")
        commands.add(inner)
        val loss = lost.firstOrNull { it.containsMatchIn(inner) }
        if (loss != null) {
            lost.remove(loss)
            // The command ran on the device; only its answer is lost.
            onDevice(inner)
            return ShellResult(255, "", "error: closed")
        }
        val output = onDevice(inner) ?: return ShellResult(1, "", "the command failed")
        val text = if (output.isEmpty() || output.endsWith("\n")) output else "$output\n"
        return ShellResult(0, "$text\nkestrel-end\n", "")
    }

    @Synchronized
    override fun host(args: List<String>, timeout: Duration): ShellResult {
        hostCommands.add(args)
        return when {
            args == listOf("get-state") -> ShellResult(0, "$state\n", "")
            args == listOf("emu", "avd", "name") -> ShellResult(0, "$consoleName\r\nOK\r\n", "")
            else -> ShellResult(0, hostOut(args), "")
        }
    }

    /** Answers a guarded command, with Android's handler running around it as [busyHandler] says. */
    private fun onDevice(command: String): String? {
        if (busyHandler) handler.removeFirstOrNull()?.invoke()
        val output = answer(command)
        if (!busyHandler) while (handler.isNotEmpty()) handler.removeFirst()()
        return output
    }

    private fun appliedFontScale(): String = fontScale ?: settings[FONT_SCALE] ?: DEFAULT_FONT_SCALE

    private fun writeFontScale(value: String?) {
        fontScale = appliedFontScale()
        if (value == null) settings.remove(FONT_SCALE) else settings[FONT_SCALE] = value
        handler.addLast(::fontScaleChanged)
    }

    /** ActivityTaskManagerService's observer of the key: it applies a scale that differs and posts its write-back. */
    private fun fontScaleChanged() {
        val scale = settings[FONT_SCALE] ?: DEFAULT_FONT_SCALE
        if (scale.toFloat() == appliedFontScale().toFloat()) return
        fontScale = scale
        handler.addLast { writeFontScale(scale) }
    }

    private fun answer(command: String): String? {
        for ((regex, reply) in handlers) {
            val match = regex.find(command) ?: continue
            return reply(match)
        }
        GETPROP.matchEntire(command)?.let { return props[it.groupValues[1]].orEmpty() }
        if (command == "wm density") return "Physical density: 420" + (density?.let { "\nOverride density: $it" } ?: "")
        WM_DENSITY.matchEntire(command)?.let { match ->
            val value = match.groupValues[1].takeIf { it != "reset" }
            density = value
            if (value == null) settings.remove("secure/display_density_forced") else settings["secure/display_density_forced"] = value
            return ""
        }
        SETTINGS_LIST.matchEntire(command)?.let { match ->
            val prefix = "${match.groupValues[1]}/"
            return settings.filterKeys { it.startsWith(prefix) }.entries.joinToString("\n") { "${it.key.removePrefix(prefix)}=${it.value}" }
        }
        LISTENER.matchEntire(command)?.let { match ->
            val (change, component) = match.destructured
            val before = settings[LISTENERS]
            val listeners = before?.split(':').orEmpty().filter { it.isNotEmpty() && it != component }
            val after = if (change == "allow" && component !in refusedListeners) listeners + component else listeners
            if (after.isEmpty()) settings.remove(LISTENERS) else settings[LISTENERS] = after.joinToString(":")
            if (listenerLag > 0) {
                listenersShown = before ?: "null"
                lagLeft = listenerLag
            }
            return ""
        }
        if (command == "settings get secure enabled_notification_listeners" && lagLeft > 0) {
            lagLeft--
            return listenersShown
        }
        if (command == "dumpsys window windows") return "  mGlobalConfiguration={${appliedFontScale()} ?mcc?mnc [en_US] ldltr sw411dp w411dp h864dp 420dpi nrml long port}"
        FONT_SCALE_WRITE.matchEntire(command)?.let { match ->
            writeFontScale(match.groupValues[2].takeIf { match.groupValues[1] == "put" }?.removeSurrounding("'"))
            return ""
        }
        SETTINGS_GET.matchEntire(command)?.let { return settings["${it.groupValues[1]}/${it.groupValues[2]}"] ?: "null" }
        SETTINGS_PUT.matchEntire(command)?.let {
            settings["${it.groupValues[1]}/${it.groupValues[2]}"] = it.groupValues[3].removeSurrounding("'")
            return ""
        }
        PM_PERMISSION.matchEntire(command)?.let { match ->
            val (change, packageName, permission) = match.destructured
            val permissions = granted.getOrPut(packageName) { mutableSetOf() }
            if (change == "revoke") permissions.remove(permission) else if (permission !in refusedGrants) permissions.add(permission)
            return ""
        }
        DUMPSYS_PACKAGE.matchEntire(command)?.let { match -> return if (match.groupValues[1] in granted) runtimePermissions(match.groupValues[1]) else "" }
        RUN_AS_CAT.matchEntire(command)?.let { return files[it.groupValues[1]] }
        BROADCAST.matchEntire(command)?.let { return broadcast(it.groupValues[1], it.groupValues[2]) }
        return ""
    }

    /** The runtime permissions' part of `dumpsys package` for [packageName], as Android prints the granted ones. */
    fun runtimePermissions(packageName: String): String =
        "    runtime permissions:\n" + granted[packageName].orEmpty().joinToString("\n") { "      $it: granted=true, flags=[ USER_SET ]" }

    private fun broadcast(action: String, extras: String): String {
        val parsed = parseExtras(extras)
        broadcasts.add(action to parsed)
        val reply = hooks[action] ?: return "Broadcasting: Intent { act=dev.kestrel.hook.$action }\nBroadcast completed: result=0"
        val (code, data) = reply(parsed)
        return "Broadcasting: Intent { act=dev.kestrel.hook.$action }\nBroadcast completed: result=$code, data=\"$data\""
    }

    companion object {
        private val GUARD = Regex("case \\$\\(getprop (\\S+)\\) in (\\S+)\\) (.*) && echo && echo kestrel-end;; \\*\\) echo kestrel-refused;; esac", RegexOption.DOT_MATCHES_ALL)
        private val GETPROP = Regex("getprop (\\S+)")
        private val WM_DENSITY = Regex("wm density (\\d+|reset)")
        private const val FONT_SCALE = "system/font_scale"
        private const val DEFAULT_FONT_SCALE = "1.0"
        private val FONT_SCALE_WRITE = Regex("settings (put|delete) system font_scale ?(.*)")
        private val SETTINGS_LIST = Regex("settings list (\\S+)")
        private val SETTINGS_GET = Regex("settings get (\\S+) (\\S+)")
        private val SETTINGS_PUT = Regex("settings put (\\S+) (\\S+) (.+)")
        private val PM_PERMISSION = Regex("pm (grant|revoke) (\\S+) (\\S+)")
        private val LISTENER = Regex("cmd notification (allow|disallow)_listener (\\S+)")
        private const val LISTENERS = "secure/enabled_notification_listeners"
        private val DUMPSYS_PACKAGE = Regex("dumpsys package (\\S+)")
        private val RUN_AS_CAT = Regex("run-as dev\\.kestrel cat (\\S+)")
        private val BROADCAST = Regex("am broadcast -n dev\\.kestrel/dev\\.kestrel\\.app\\.debug\\.TestHookReceiver -a dev\\.kestrel\\.hook\\.(\\S+)(.*)", RegexOption.DOT_MATCHES_ALL)

        /** The extras of `--es name 'text'`, `--ez name true` and `--ei name 5`, with the shell's quoting undone. */
        fun parseExtras(text: String): Map<String, String> {
            val extras = linkedMapOf<String, String>()
            var at = 0
            fun skipSpaces() {
                while (at < text.length && text[at] == ' ') at++
            }
            fun word(): String {
                skipSpaces()
                val builder = StringBuilder()
                while (at < text.length && text[at] != ' ') {
                    if (text[at] == '\'') {
                        at++
                        while (text[at] != '\'') builder.append(text[at++])
                        at++
                        // '\'' is a quote that the shell's quoting closed, escaped and opened again.
                        if (text.startsWith("\\''", at)) {
                            builder.append('\'')
                            at += 2
                        }
                    } else {
                        builder.append(text[at++])
                    }
                }
                return builder.toString()
            }
            while (true) {
                skipSpaces()
                if (at >= text.length) break
                word()
                val name = word()
                extras[name] = word()
            }
            return extras
        }
    }
}
