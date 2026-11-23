package dev.kestrel.eval.run

import dev.kestrel.eval.device.Device
import dev.kestrel.eval.hook.Hooks
import java.nio.file.Files
import java.nio.file.Path

/**
 * The scripted model of a run without a model (design D5 of sub-project 6; task-check.ps1's `Install-TaskScript`): the
 * filled script goes to `/data/local/tmp`, `run-as dev.kestrel` copies it into Kestrel's `files/agent-scripts/task.json`,
 * and the hook agent-script installs it as the task's model. The scripts are resources of `dev/kestrel/eval/scripts`.
 */
class ScriptInstaller(private val device: Device, private val hooks: Hooks, private val work: Path) {
    fun install(text: String) {
        Files.createDirectories(work)
        val local = work.resolve("task.json")
        Files.writeString(local, text)
        device.push(local, TEMPORARY)
        device.idempotent(
            "run-as dev.kestrel sh -c 'mkdir -p files/agent-scripts && cat $TEMPORARY > files/agent-scripts/task.json' && rm -f $TEMPORARY",
        )
        val answer = hooks.agentScript("task.json")
        check(answer["script"] == "installed") { "agent-script did not install the script." }
    }

    fun remove() {
        hooks.agentScriptClear()
    }

    companion object {
        private const val TEMPORARY = "/data/local/tmp/kestrel-task.json"

        /**
         * The text of the script [name], a resource of `dev/kestrel/eval/scripts`. It is read as a stream, because the
         * privacy audit counts a read through a URL as network access.
         */
        fun resource(name: String): String =
            ScriptInstaller::class.java.getResourceAsStream("/dev/kestrel/eval/scripts/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalArgumentException("No script $name in dev/kestrel/eval/scripts.")
    }
}
