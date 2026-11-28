package dev.kestrel.app.debug

/**
 * Calls a test hook through its real transport, as `scripts/hook.ps1` does: `content call` in the device's shell, which
 * reaches [TestHookProvider] as the shell's user, on a binder thread of Kestrel's process. [shell] runs a command as the
 * shell's user (UiAutomation), which splits it at spaces and groups nothing in quotes, so no value may hold a space.
 */
internal class ShellHooks(private val shell: (String) -> String) {
    fun call(command: String, vararg extras: Pair<String, Any>): HookReply {
        val bindings = extras.joinToString("") { (name, value) -> " --extra ${binding(name, value)}" }
        return parse(shell("content call --uri content://${TestHookProvider.AUTHORITY} --method $command$bindings"))
    }

    companion object {
        /** `content call`'s typed extra `<name>:<type>:<value>`, with `\` and `:` in the value escaped as `content` reads them. */
        fun binding(name: String, value: Any): String {
            val type = when (value) {
                is String -> "s"
                is Boolean -> "b"
                is Int -> "i"
                is Float -> "f"
                else -> throw IllegalArgumentException("A hook's extra is a String, Boolean, Int or Float, not ${value::class.simpleName}")
            }
            val text = value.toString()
            require(text.none { it.isWhitespace() }) { "The shell splits '$text' at its spaces" }
            return "$name:$type:" + text.replace("\\", "\\\\").replace(":", "\\:")
        }

        /** The reply that `content call` printed: `Result: Bundle[{code=<code>, text=<text>}]` (TestHookProvider). */
        fun parse(output: String): HookReply {
            val match = RESULT.matchEntire(output.removeSuffix("\n")) ?: throw AssertionError("content call did not answer with a hook's reply:\n$output")
            return HookReply(match.groupValues[1].toInt(), match.groupValues[2])
        }

        private val RESULT = Regex("Result: Bundle\\[\\{code=(-?\\d+), text=(.*)\\}\\]", RegexOption.DOT_MATCHES_ALL)
    }
}
