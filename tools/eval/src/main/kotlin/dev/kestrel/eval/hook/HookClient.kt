package dev.kestrel.eval.hook

import dev.kestrel.eval.device.Deadline
import dev.kestrel.eval.device.Device
import kotlin.time.Duration.Companion.seconds

/** An argument of a hook: one of `content call`'s typed extras, a text (`s`), a number (`i`) or a flag (`b`). */
sealed interface Extra {
    val name: String

    /** The extra's type letter in `content call`'s `<name>:<type>:<value>`. */
    val type: String

    /** The extra's value as text. */
    val text: String

    /**
     * The extra as the device's shell reads it, `--extra '<name>:<type>:<value>'`, with `\` and `:` in the value escaped as
     * `content` reads them, as scripts\lib\HookCall.ps1's Format-HookExtra writes it.
     */
    fun shell(): String = "--extra " + quote("$name:$type:" + text.replace("\\", "\\\\").replace(":", "\\:"))

    data class Text(override val name: String, val value: String) : Extra {
        override val type: String get() = "s"
        override val text: String get() = value
    }

    data class Number(override val name: String, val value: Int) : Extra {
        override val type: String get() = "i"
        override val text: String get() = value.toString()
    }

    data class Flag(override val name: String, val value: Boolean) : Extra {
        override val type: String get() = "b"
        override val text: String get() = value.toString()
    }

    companion object {
        /** A text in single quotes for the device's shell, with each `'` as `'\''`, as scripts\hook.ps1 quotes it. */
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}

/** A hook's answer: one line of `name=value` pairs, never screen text; a word without `=`, such as "answered", is kept in [text] only. */
class HookAnswer(val text: String) {
    val pairs: Map<String, String> = text.trim().split(Regex("\\s+")).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    operator fun get(name: String): String? = pairs[name]

    /** The pair as a number, 0 when it is missing or no number. */
    fun int(name: String): Int = pairs[name]?.toIntOrNull() ?: 0

    override fun toString(): String = text

    companion object {
        val EMPTY = HookAnswer("")
    }
}

/** Kestrel answered the hook with an error (code 1), whose reason Kestrel gave; it did not act. */
class HookError(message: String) : Exception(message)

/** Kestrel did not answer the hook, because it has no test hooks: it is not installed, or not a debug build. */
class HookSilent(command: String) : Exception("Kestrel did not answer $command. Install a debug build with scripts\\install.ps1.")

/**
 * The answer of a hook was lost: adb lost it, the call ran out of its time, or the call reached Kestrel and failed on its
 * way back, as when Kestrel's process ended during the call. Kestrel may have acted, so the caller reads the state back
 * instead of sending a hook that acts again.
 */
class HookLost(command: String, reason: String? = null) : Exception("The answer of $command was lost" + (reason?.let { ": $it" } ?: "."))

/**
 * Kestrel refused the hooks' caller: its provider answers only adb's shell (uid 2000) and root (uid 0). No hook can
 * work for this caller, so this stops the series instead of counting as one hook's refusal.
 */
class HookCallerRefused(command: String, reason: String) :
    Exception("Kestrel refused the caller of $command: $reason. Only adb's shell (uid 2000) and root (uid 0) may use its test hooks.")

/** What `content call` printed for a hook, read as one outcome of the transport ([HookClient.outcome]). */
sealed interface HookOutcome {
    /** Code 0: Kestrel's answer. */
    data class Answered(val text: String) : HookOutcome

    /** Code 1: Kestrel's own error answer, with its reason. */
    data class Failed(val reason: String) : HookOutcome

    /** No provider has the hooks' authority: a Kestrel without hooks, or none. */
    data object NoHooks : HookOutcome

    /** A SecurityException: Kestrel, or Android at the acquisition, refused the caller. */
    data class CallerRefused(val reason: String) : HookOutcome

    /** Any other output, such as a DeadObjectException when Kestrel's process ended during the call. */
    data class Lost(val reason: String) : HookOutcome
}

/**
 * The debug hooks' transport (design D3 of sub-project 6; plan 7G's amendment "The hooks' transport"): `content call --uri
 * content://dev.kestrel.hooks --method <COMMAND> <extras>`, which Kestrel's debug-only TestHookProvider answers, as
 * scripts\hook.ps1 sends it, with `content`'s error output joined to its answer. A hook that reads ([retry]) goes through
 * [Device.read], which asks again after a lost answer, also one that the call lost on the device; one that acts goes
 * through [Device.once]. The answer is `Result: Bundle[{code=<code>, text=<text>}]`: code 0 is an answer and 1 an error
 * whose text is the reason; the text is all that stands between `text=` and the last `}]`, so it may hold any character.
 * The other outputs are `content`'s errors, which [outcome] tells apart as scripts\lib\HookCall.ps1 does: a missing
 * provider ([HookSilent]), a refused caller ([HookCallerRefused]), and every other failure of the call, which is lost
 * ([HookLost]), never Kestrel's refusal.
 */
class HookClient(private val device: Device) {
    /** The hooks for a wait that ends at [deadline] ([Device.until]). */
    fun until(deadline: Deadline): HookClient = HookClient(device.until(deadline))

    fun call(command: String, extras: List<Extra>, retry: Boolean): HookAnswer {
        val call = command(command, extras) + " 2>&1"
        val lines = if (retry) {
            device.read(call, TIMEOUT) { output -> (outcome(output.joinToString("\n")) as? HookOutcome.Lost)?.reason }
        } else {
            device.once(call, TIMEOUT) ?: throw HookLost(command)
        }
        return answer(command, lines.joinToString("\n"))
    }

    companion object {
        private val TIMEOUT = 60.seconds
        private const val AUTHORITY = "dev.kestrel.hooks"
        private const val ANSWERED = 0
        private const val FAILED = 1
        private val RESULT = Regex("Result: Bundle\\[\\{code=(-?\\d+), text=(.*)\\}\\]\\s*", RegexOption.DOT_MATCHES_ALL)
        private val SECURITY = Regex("^java\\.lang\\.SecurityException\\b.*$", RegexOption.MULTILINE)

        /** The device command of the hook [command], such as goal-control, whose method is GOAL_CONTROL. */
        fun command(command: String, extras: List<Extra>): String =
            "content call --uri content://$AUTHORITY --method ${command.uppercase().replace('-', '_')}" + extras.joinToString("") { " " + it.shell() }

        /** The outcome of a hook in [output], what `content call` printed with its errors. */
        fun outcome(output: String): HookOutcome {
            RESULT.matchEntire(output)?.let { match ->
                val text = match.groupValues[2]
                return when (val code = match.groupValues[1].toInt()) {
                    ANSWERED -> HookOutcome.Answered(text)
                    FAILED -> HookOutcome.Failed(text)
                    else -> HookOutcome.Lost("its answer had the unknown code $code: $text")
                }
            }
            if ("Could not find provider: $AUTHORITY" in output) return HookOutcome.NoHooks
            SECURITY.find(output)?.let { return HookOutcome.CallerRefused(it.value.trim()) }
            return HookOutcome.Lost(reason(output))
        }

        /** The hook [command]'s answer in [output], or the exception of its outcome. */
        fun answer(command: String, output: String): HookAnswer = when (val outcome = outcome(output)) {
            is HookOutcome.Answered -> HookAnswer(outcome.text)
            is HookOutcome.Failed -> throw HookError(outcome.reason)
            HookOutcome.NoHooks -> throw HookSilent(command)
            is HookOutcome.CallerRefused -> throw HookCallerRefused(command, outcome.reason)
            is HookOutcome.Lost -> throw HookLost(command, outcome.reason)
        }

        // The exception that `content` printed after its "Error while accessing provider", without the stack trace; the
        // whole output when it printed none.
        private fun reason(output: String): String {
            val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return lines.firstOrNull { !it.startsWith("Error while accessing provider") && !it.startsWith("at ") } ?: lines.joinToString(" ").ifEmpty { "no output" }
        }
    }
}