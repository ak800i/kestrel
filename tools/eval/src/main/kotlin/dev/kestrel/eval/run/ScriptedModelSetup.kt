package dev.kestrel.eval.run

import dev.kestrel.eval.task.ProcessSetup
import dev.kestrel.eval.task.RunScope
import dev.kestrel.eval.task.SetupResult
import dev.kestrel.eval.task.Task

/**
 * The scripted model of a run without a model (design D5 of sub-project 6), a step that holds state in Kestrel's process
 * (plan 7G): [script], filled with the run's values, is installed before the run's first command, and its removal is
 * among the run's restores. After each restart of Kestrel ([renew]) the next script of [task]'s dry run
 * ([dev.kestrel.eval.task.DryRun.restarts]) is installed, because the scripted model ended with the process. Each install
 * is a change under the restores' guard ([dev.kestrel.eval.run.Restores.change]), as the self-chat aliases' are: once Ctrl+C
 * began the restores, no install starts, and the restores wait for one under way, so no scripted model outlives the run.
 */
internal class ScriptedModelSetup(private val scripts: ScriptInstaller, private val task: Task, private val script: String) : ProcessSetup {
    private var restarts = 0

    override fun apply(scope: RunScope): SetupResult {
        scope.restores.push("the scripted model removed", byHand(scope)) { scripts.remove() }
        install(scope, script)
        return SetupResult.Ready
    }

    override fun renew(scope: RunScope) {
        val next = task.dryRun?.restarts?.getOrNull(restarts++) ?: error("the dry run of ${task.name} has no script for its restart")
        install(scope, ScriptInstaller.resource(next))
    }

    private fun install(scope: RunScope, text: String) {
        scope.restores.change("the scripted model installed", byHand(scope)) { scripts.install(scope.values.fill(text, json = true)) }
    }

    private fun byHand(scope: RunScope) = "Run: scripts\\hook.ps1 -Serial ${scope.device.serial} agent-script -Clear"
}
