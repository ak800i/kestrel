package dev.kestrel.eval.hook

import dev.kestrel.eval.device.Device
import kotlin.time.Duration.Companion.seconds

/** An extra of a hook's broadcast: `--es` text, `--ei` number or `--ez` flag. */
sealed interface Extra {
    val name: String

    /** The extra as the device's shell reads it. */
    fun shell(): String

    data class Text(override val name: String, val value: String) : Extra {
        override fun shell(): String = "--es $name ${quote(value)}"
    }

    data class Number(override val name: String, val value: Int) : Extra {
        override fun shell(): String = "--ei $name $value"
    }

    data class Flag(override val name: String, val value: Boolean) : Extra {
        override fun shell(): String = "--ez $name $value"
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

/** Kestrel answered the hook with an error, whose reason the device gave. */
class HookError(message: String) : Exception(message)

/** Kestrel did not answer the hook (result 0), as when it is stopped or not a debug build. */
class HookSilent(command: String) : Exception("Kestrel did not answer $command. Install a debug build with scripts\\install.ps1.")

/** The answer of a hook that acts was lost; the caller reads the state back instead of sending it again. */
class HookLost(command: String) : Exception("The answer of $command was lost.")

/**
 * The debug hooks' broadcast (design D3 of sub-project 6): `am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver
 * -a dev.kestrel.hook.<COMMAND> <extras>`, as scripts\hook.ps1 sends it. A hook that reads ([retry]) goes through
 * [Device.read], one that acts through [Device.once]. The answer is `Broadcast completed: result=<code>, data="<text>"`:
 * -1 is an answer, 0 means that Kestrel did not answer, and any other code is an error whose data is the reason.
 */
class HookClient(private val device: Device) {
    fun call(command: String, extras: List<Extra>, retry: Boolean): HookAnswer {
        val broadcast = broadcast(command, extras)
        val lines = if (retry) device.read(broadcast, TIMEOUT) else device.once(broadcast, TIMEOUT) ?: throw HookLost(command)
        val match = ANSWER.find(lines.joinToString("\n")) ?: throw HookError("The device's answer to $command was not a broadcast's answer.")
        val data = match.groupValues[2]
        return when (match.groupValues[1].toInt()) {
            -1 -> HookAnswer(data)
            0 -> throw HookSilent(command)
            else -> throw HookError(data)
        }
    }

    companion object {
        private val TIMEOUT = 60.seconds
        private val ANSWER = Regex("Broadcast completed: result=(-?\\d+)(?:, data=\"(.*)\")?\\s*$", RegexOption.DOT_MATCHES_ALL)

        fun broadcast(command: String, extras: List<Extra>): String =
            "am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a dev.kestrel.hook.${command.uppercase().replace('-', '_')}" +
                extras.joinToString("") { " " + it.shell() }
    }
}
