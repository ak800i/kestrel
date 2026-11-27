package dev.kestrel.eval.run

import dev.kestrel.eval.device.Device
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.hook.Hooks
import java.nio.file.Files
import java.nio.file.Path

/**
 * The scripted model of a run without a model (design D5 of sub-project 6; task-check.ps1's `Install-TaskScript`): the
 * filled script goes to `/data/local/tmp`, `run-as dev.kestrel` copies it into Kestrel's `files/agent-scripts/task.json`,
 * and the hook agent-script installs it as the task's model. [check] asks Kestrel's scripted model whether it takes a
 * script, by its own rules, without installing it (plan 7G). The scripts are resources of `dev/kestrel/eval/scripts`.
 */
class ScriptInstaller(private val device: Device, private val hooks: Hooks, private val work: Path) {
    fun install(text: String) {
        put(INSTALLED, text)
        val answer = hooks.agentScript(INSTALLED)
        check(answer["script"] == "installed") { "agent-script did not install the script." }
    }

    /**
     * Kestrel's scripted model reads [text] by its own rules and installs nothing: `script=valid replies=<n>`, or a
     * [dev.kestrel.eval.hook.HookError] that names the error's kind, the reply's number and the keys. The file goes into
     * Kestrel's folder of scripts under its own name and leaves it again.
     */
    fun check(text: String): HookAnswer {
        put(CHECKED, text)
        try {
            return hooks.agentScriptCheck(CHECKED)
        } finally {
            device.idempotent("run-as dev.kestrel rm -f files/agent-scripts/$CHECKED")
        }
    }

    fun remove() {
        hooks.agentScriptClear()
    }

    // [text] as Kestrel's files/agent-scripts/[name], through /data/local/tmp, which keeps no copy.
    private fun put(name: String, text: String) {
        Files.createDirectories(work)
        val local = work.resolve(name)
        Files.writeString(local, text)
        device.push(local, TEMPORARY)
        device.idempotent(
            "run-as dev.kestrel sh -c 'mkdir -p files/agent-scripts && cat $TEMPORARY > files/agent-scripts/$name' && rm -f $TEMPORARY",
        )
    }

    companion object {
        private const val TEMPORARY = "/data/local/tmp/kestrel-task.json"
        private const val INSTALLED = "task.json"
        private const val CHECKED = "check.json"

        /**
         * The text of the script [name], a resource of `dev/kestrel/eval/scripts`. It is read as a stream, because the
         * privacy audit counts a read through a URL as network access.
         */
        fun resource(name: String): String =
            ScriptInstaller::class.java.getResourceAsStream("/dev/kestrel/eval/scripts/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalArgumentException("No script $name in dev/kestrel/eval/scripts.")
    }
}
