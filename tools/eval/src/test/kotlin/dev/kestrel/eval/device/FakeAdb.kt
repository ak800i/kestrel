package dev.kestrel.eval.device

import kotlin.time.Duration

/**
 * A small device for the tests (design D3 of sub-project 6): properties, settings, files, the answers of commands and of
 * the debug hooks by action, and every command that reached it. A guarded command runs only when the guard's property
 * matches its pattern, as on a real device; [loseNext] loses the next answer of a matching command, as adb does when a
 * device goes away for a moment, [slow] makes every command take time on a [FakeClock], and [hookCalls] gives the extras
 * of each hook's call.
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

    /** The time that each call of adb got, in their order. */
    val timeouts = mutableListOf<Duration>()
    private var slowness: Slowness? = null

    private class Slowness(val clock: FakeClock, val latency: Duration, val only: (String) -> Boolean)

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
    // By default no conversation is open, none of the hooks' own is left, and so no goal or request of theirs runs to stop
    // (design 14.4 of sub-project 7; plan 7G).
    private val hooks = mutableMapOf<String, (Map<String, String>) -> Pair<Int, String>>(
        "CONVERSATION_STATE" to { _ -> ANSWERED to NO_CONVERSATION },
        "CONVERSATION_CONTROL" to { _ -> ANSWERED to NOTHING_DELETED },
        "GOAL_CONTROL" to { extras -> ANSWERED to if (extras["do"] == "stop-typed") "stop=none" else "${extras["do"]}=sent" },
        "VOICE_RESTORE" to { _ -> ANSWERED to "restoredAgo=none guard=120" },
    )
    private val hookCalls = mutableListOf<Pair<String, Map<String, String>>>()
    private val lost = mutableListOf<Regex>()
    private val dying = mutableListOf<Regex>()

    /** Answers a command that matches [pattern] (the newest rule wins) with its output, or null for a failed command. */
    fun on(pattern: String, reply: (MatchResult) -> String?) {
        handlers.add(0, Regex(pattern) to reply)
    }

    /** Answers the debug hook [method] (such as AGENT_TASK) with `name=value` pairs, as code 0. */
    fun hook(method: String, answer: (Map<String, String>) -> String) {
        hooks[method] = { extras -> ANSWERED to answer(extras) }
    }

    /** Answers the debug hook [method] with a code and its text: [ANSWERED] and the answer, or [FAILED] and the reason. */
    fun hookWith(method: String, answer: (Map<String, String>) -> Pair<Int, String>) {
        hooks[method] = answer
    }

    /** Answers the debug hook [method] with an error and its reason. */
    fun hookFails(method: String, reason: String) {
        hooks[method] = { _ -> FAILED to reason }
    }

    /** Loses the next answer of a command that matches [pattern]: the command reached the device, but adb gave no answer. */
    fun loseNext(pattern: String) {
        lost.add(Regex(pattern))
    }

    /**
     * Ends Kestrel's process during the next call of a hook that matches [pattern]: the hook ran, but `content call` prints
     * [DEAD_CALL] instead of its answer, as on kestrel-api37 when Kestrel's process ended during a call.
     */
    fun dieNext(pattern: String) {
        dying.add(Regex(pattern))
    }

    /**
     * Makes adb slow, as on a busy device or a bad cable: each call, or with [only] each call whose command matches, takes
     * [latency] on [clock], and one that would take longer than its timeout ends at the timeout without an answer, as
     * [ProcessAdb] ends it, before it reaches the device.
     */
    fun slow(clock: FakeClock, latency: Duration, only: (String) -> Boolean = { true }) {
        slowness = Slowness(clock, latency, only)
    }

    /** The extras of each call of the hook [method], in their order. */
    fun hookCalls(method: String): List<Map<String, String>> = hookCalls.filter { it.first == method }.map { it.second }

    // Synchronized, because a test's shutdown hook may run restores while the main thread still sends commands.
    @Synchronized
    override fun shell(command: String, timeout: Duration): ShellResult {
        take(timeout, command)
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
        val death = dying.firstOrNull { it.containsMatchIn(inner) }
        if (death != null) {
            dying.remove(death)
            onDevice(inner)
            return ShellResult(0, "$DEAD_CALL\nkestrel-end\n", "")
        }
        val output = onDevice(inner) ?: return ShellResult(1, "", "the command failed")
        val text = if (output.isEmpty() || output.endsWith("\n")) output else "$output\n"
        return ShellResult(0, "$text\nkestrel-end\n", "")
    }

    @Synchronized
    override fun host(args: List<String>, timeout: Duration): ShellResult {
        take(timeout, args.joinToString(" "))
        hostCommands.add(args)
        return when {
            args == listOf("get-state") -> ShellResult(0, "$state\n", "")
            args == listOf("emu", "avd", "name") -> ShellResult(0, "$consoleName\r\nOK\r\n", "")
            else -> ShellResult(0, hostOut(args), "")
        }
    }

    private fun take(timeout: Duration, command: String) {
        timeouts.add(timeout)
        val slow = slowness?.takeIf { it.only(command) } ?: return
        if (slow.latency > timeout) {
            slow.clock.time += timeout
            throw AdbException("adb did not end within $timeout.")
        }
        slow.clock.time += slow.latency
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
        HOOK_CALL.matchEntire(command)?.let { return hookCall(it.groupValues[1], it.groupValues[2]) }
        return ""
    }

    /** The runtime permissions' part of `dumpsys package` for [packageName], as Android prints the granted ones. */
    fun runtimePermissions(packageName: String): String =
        "    runtime permissions:\n" + granted[packageName].orEmpty().joinToString("\n") { "      $it: granted=true, flags=[ USER_SET ]" }

    // As `content call` prints TestHookProvider's Bundle; a hook that no rule answers is a Kestrel without hooks.
    private fun hookCall(method: String, extras: String): String {
        val parsed = parseExtras(extras)
        hookCalls.add(method to parsed)
        val reply = hooks[method] ?: return NO_HOOKS
        val (code, text) = reply(parsed)
        return "Result: Bundle[{code=$code, text=$text}]\n"
    }

    companion object {
        /** conversation-state's answer while no conversation is open (design 14.4 of sub-project 7). */
        const val NO_CONVERSATION = "open=no continued=no commands=0 goal=none request=none quiet=off quietLeft=none replyChannel=voice testMode=on typed=no"

        /** conversation-control do=delete-typed's answer when none of the hooks' own conversations is left (plan 7F). */
        const val NOTHING_DELETED = "deleted=0 open=0 failed=0"

        /** The codes of TestHookProvider's answer: an answer, or an error whose text is the reason. */
        const val ANSWERED = 0
        const val FAILED = 1

        /** What `content call` printed on kestrel-api37 for an authority that no app has, as for a Kestrel without hooks. */
        const val NO_HOOKS = "Error while accessing provider:dev.kestrel.hooks\n" +
            "java.lang.IllegalStateException: Could not find provider: dev.kestrel.hooks\n" +
            "\tat com.android.commands.content.Content\$Command.execute(Content.java:519)\n" +
            "\tat com.android.commands.content.Content.main(Content.java:735)\n"

        /** What `content call` printed on kestrel-api37 when Kestrel's process ended during the call (`kill -9` during WATCH). */
        const val DEAD_CALL = "Error while accessing provider:dev.kestrel.hooks\n" +
            "android.os.DeadObjectException\n" +
            "\tat android.os.BinderProxy.transactNative(Native Method)\n" +
            "\tat android.os.BinderProxy.transact(BinderProxy.java:626)\n" +
            "\tat android.content.ContentProviderProxy.call(ContentProviderNative.java:764)\n" +
            "\tat com.android.commands.content.Content\$CallCommand.onExecute(Content.java:599)\n" +
            "\tat com.android.commands.content.Content\$Command.execute(Content.java:522)\n" +
            "\tat com.android.commands.content.Content.main(Content.java:735)\n" +
            "\tat com.android.internal.os.RuntimeInit.nativeFinishInit(Native Method)\n" +
            "\tat com.android.internal.os.RuntimeInit.main(RuntimeInit.java:367)\n"

        /** What `content call` printed on kestrel-api37 for a caller that is not adb's shell (`run-as dev.kestrel content call`). */
        const val REFUSED_CALLER = "Error while accessing provider:dev.kestrel.hooks\n" +
            "java.lang.SecurityException: Permission Denial: Do not have permission in call getContentProviderExternal() from pid=4343, " +
            "uid=10353 requires android.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY\n" +
            "\tat android.os.Parcel.createExceptionOrNull(Parcel.java:3392)\n" +
            "\tat android.os.Parcel.createException(Parcel.java:3376)\n" +
            "\tat android.os.Parcel.readException(Parcel.java:3359)\n" +
            "\tat android.os.Parcel.readException(Parcel.java:3301)\n" +
            "\tat android.app.IActivityManager\$Stub\$Proxy.getContentProviderExternal(IActivityManager.java:8487)\n" +
            "\tat com.android.commands.content.Content\$Command.execute(Content.java:516)\n" +
            "\tat com.android.commands.content.Content.main(Content.java:735)\n" +
            "\tat com.android.internal.os.RuntimeInit.nativeFinishInit(Native Method)\n" +
            "\tat com.android.internal.os.RuntimeInit.main(RuntimeInit.java:367)\n" +
            "Caused by: android.os.RemoteException: Remote stack trace:\n" +
            "\tat com.android.server.wm.Session\$\$ExternalSyntheticBUOutline1.m(R8\$\$SyntheticClass:0)\n" +
            "\tat com.android.server.am.ActivityManagerService.enforceCallingPermission(ActivityManagerService.java:6997)\n" +
            "\tat com.android.server.am.ContentProviderHelper.getContentProviderExternal(ContentProviderHelper.java:157)\n" +
            "\tat com.android.server.am.ActivityManagerService.getContentProviderExternal(ActivityManagerService.java:7857)\n" +
            "\tat android.app.IActivityManager\$Stub.onTransact(IActivityManager.java:4376)\n"

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
        private val HOOK_CALL = Regex("content call --uri content://dev\\.kestrel\\.hooks --method (\\S+)(.*) 2>&1", RegexOption.DOT_MATCHES_ALL)

        /**
         * The extras of `--extra 'name:s:text'`, `--extra 'name:b:true'` and `--extra 'name:i:5'` by their names, with the
         * shell's quoting and `content`'s escapes of `\` and `:` undone.
         */
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
                check(word() == "--extra") { "Not a hook's extra: $text" }
                val parts = binding(word())
                extras[parts[0]] = parts[2]
            }
            return extras
        }

        // `content`'s `<name>:<type>:<value>`, split at the colons that no `\` escapes.
        private fun binding(text: String): List<String> {
            val parts = mutableListOf<String>()
            val part = StringBuilder()
            var at = 0
            while (at < text.length) {
                when (val c = text[at++]) {
                    '\\' -> part.append(text[at++])
                    ':' -> {
                        parts.add(part.toString())
                        part.clear()
                    }
                    else -> part.append(c)
                }
            }
            parts.add(part.toString())
            check(parts.size == 3) { "Not a binding of content call: $text" }
            return parts
        }
    }
}
