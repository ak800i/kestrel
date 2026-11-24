package dev.kestrel.agent.loop

import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.agent.port.CheckedLaunch
import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.safety.ActionKind
import dev.kestrel.safety.LinkCheck
import java.net.URI

/**
 * The task's launch check (spec 12.1 and 12.5; plan 6B-basefix): every app, link, page or app screen that a tool opens
 * becomes a [CheckedLaunch] here, judged with the task's agent-free apps and LinkCheck's rules, or a refusal that the model
 * reads. [CheckedLaunch.Judge] does the judging, and nothing else can make a [CheckedLaunch].
 */
internal class Launches(private val run: TaskRun) {
    private val judge = CheckedLaunch.Judge(run.ports.screen, run.settings::closed) { link, kind, target ->
        LinkCheck.check(link, kind, fromOwner(link), target, run.settings::closed, run.settings.emergency, phonePackages = run.settings.phonePackages)
    }

    sealed interface Launch {
        class Go(val launch: CheckedLaunch) : Launch

        class Stop(val outcome: Outcome) : Launch
    }

    /** [packageName] as its launcher icon opens it. */
    suspend fun app(packageName: String): Launch = launch(judge.app(packageName))

    /** [spec] as it will be sent; a link in it is judged as a link of [linkKind]. */
    suspend fun intent(spec: IntentSpec, linkKind: ActionKind = ActionKind.OPEN_DEEP_LINK): Launch = launch(judge.intent(spec, linkKind))

    private suspend fun launch(judgement: CheckedLaunch.Judgement): Launch = when (judgement) {
        is CheckedLaunch.Judgement.Cleared -> Launch.Go(judgement.launch)
        CheckedLaunch.Judgement.NotResolved -> Launch.Stop(Outcome.Failed(NOT_RESOLVED))
        is CheckedLaunch.Judgement.Closed -> Launch.Stop(Outcome.Refused("${quoted(run.label(judgement.packageName) ?: judgement.packageName)} is agent-free"))
        is CheckedLaunch.Judgement.LinkRefused -> Launch.Stop(Outcome.Refused(judgement.reason))
    }

    /**
     * Whether [link] came from the owner: its host (or for a deep link, the whole link) is in his request (D32 item 12),
     * its words as a whole run of the request's words, or the host as one spoken word ("wikipedia.org" said "wikipedia
     * org"). Only an https link has a host that names a place: in `whatsapp://send?…`, `viber://forward?…` or `tg://share?…`
     * the "host" is an action word that most messaging requests hold (D12).
     */
    fun fromOwner(link: String): Boolean {
        val uri = try {
            URI(link)
        } catch (e: Exception) {
            null
        }
        val host = uri?.host?.takeIf { uri.scheme.equals(HTTPS, ignoreCase = true) } ?: link
        val words = Normalizer.words(host)
        val request = Normalizer.words(run.task.text)
        if (words.isEmpty()) return false
        val joined = words.joinToString("")
        return request.windowed(words.size).any { it == words } || request.any { it == joined }
    }

    private fun quoted(text: String): String = ScreenText.quote(run.redactor.redact(text), ScreenText.LABEL_LIMIT)

    override fun toString(): String = "Launches"

    companion object {
        const val NOT_RESOLVED = "Android does not open it in that app now, so Kestrel opened nothing"
        private const val HTTPS = "https"
    }
}
