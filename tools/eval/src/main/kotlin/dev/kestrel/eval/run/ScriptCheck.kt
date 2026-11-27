package dev.kestrel.eval.run

import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.hook.HookError
import dev.kestrel.eval.task.RunValues

/**
 * Before the first run of a command without a model (plan 7G): every script that its runs will install, each task's
 * script and the scripts after its restarts, is read by Kestrel's own scripted model ([ScriptInstaller.check]), filled with
 * sample values as a run fills them with its own. The rule of what a script may hold lives in one place, the scripted
 * model, which also installs the scripts; `:tools:eval` depends on no Kestrel module. A script that the model refuses
 * stops the command with a [Refusal] that names the file and Kestrel's reason (the error's kind, the reply's number and
 * the keys), never a text, before any run starts. A build without the check would install the script instead, so its
 * answer `script=installed` removes the scripted model again and refuses the build.
 */
class ScriptCheck(private val installer: ScriptInstaller, private val serial: String) {
    fun check(names: List<String>) {
        for (name in names.distinct()) {
            val answer = try {
                installer.check(SAMPLE.fill(ScriptInstaller.resource(name), json = true))
            } catch (e: HookError) {
                throw Refusal("Kestrel's scripted model does not take the script $name (${e.message}); fix the script in dev/kestrel/eval/scripts before the runs.")
            }
            when (answer["script"]) {
                "valid" -> Unit
                "installed" -> {
                    installer.remove()
                    throw Refusal(
                        "Kestrel on $serial answers no agent-script check, as a build from before plan 7G of sub-project 7 does; install the current debug build with scripts\\install.ps1.",
                    )
                }
                else -> throw Refusal("Kestrel on $serial answered agent-script's check of $name without its result.")
            }
        }
    }

    private companion object {
        // A run's values as a run fills them, the hour and the minute as numbers, for the check of a script's form.
        val SAMPLE = RunValues(
            "maple heron", name = "Pera", time = "07:30", number = "0601234567", address = "pera.test@example.com",
            date = "2026-11-25", hour = "7", minute = "30",
        )
    }
}
