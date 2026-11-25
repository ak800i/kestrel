package dev.kestrel.agent.loop

import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.agent.port.ActionOutcome
import dev.kestrel.agent.port.CheckedLaunch
import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.safety.ActionKind
import dev.kestrel.safety.LinkCheck

/**
 * The task's launch check (spec 12.1 and 12.5; plan 6B-basefix): every app, link, page or app screen that a tool opens
 * becomes a [CheckedLaunch] here, judged with the task's agent-free apps and LinkCheck's rules, or a refusal that the model
 * reads, and opens only through [open]. [CheckedLaunch.Judge] does the judging, and nothing else can make a [CheckedLaunch].
 */
internal class Launches(private val run: TaskRun) {
    private val judge = CheckedLaunch.Judge(run.ports.screen, run.settings::closed) { link, kind, target ->
        LinkCheck.check(link, kind, run.safety, target, run.settings::closed, run.settings.emergency, run.settings.phonePackages)
    }

    sealed interface Launch {
        class Go(val launch: CheckedLaunch) : Launch

        class Stop(val outcome: Outcome) : Launch
    }

    /** [packageName] as its launcher icon opens it. */
    suspend fun app(packageName: String): Launch = launch(judge.app(packageName))

    /** [spec] as it will be sent; a link in it is judged as a link of [linkKind]. */
    suspend fun intent(spec: IntentSpec, linkKind: ActionKind = ActionKind.OPEN_DEEP_LINK): Launch = launch(judge.intent(spec, linkKind))

    /**
     * Opens [launch], as every tool and the return to the task's app do. The app that opened is one that the run used,
     * which the command's record names, also when no later step acts in it (design 8.1 of sub-project 7).
     */
    suspend fun open(launch: CheckedLaunch): ActionOutcome {
        val outcome = run.ports.screen.act(ScreenAction.Launch(launch))
        if (outcome.done) launch.target?.let { run.trace.acted(it) }
        return outcome
    }

    private suspend fun launch(judgement: CheckedLaunch.Judgement): Launch = when (judgement) {
        is CheckedLaunch.Judgement.Cleared -> Launch.Go(judgement.launch)
        CheckedLaunch.Judgement.NotResolved -> Launch.Stop(Outcome.Failed(NOT_RESOLVED))
        is CheckedLaunch.Judgement.Closed -> Launch.Stop(Outcome.Refused("${quoted(run.label(judgement.packageName) ?: judgement.packageName)} is agent-free"))
        is CheckedLaunch.Judgement.LinkRefused -> Launch.Stop(Outcome.Refused(judgement.reason))
    }

    private fun quoted(text: String): String = ScreenText.quote(run.redactor.redact(text), ScreenText.LABEL_LIMIT)

    override fun toString(): String = "Launches"

    companion object {
        const val NOT_RESOLVED = "Android does not open it in that app now, so Kestrel opened nothing"
    }
}
