package dev.kestrel.agent.loop

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.port.GlobalKind
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.HandOver
import dev.kestrel.safety.HandOverKind
import dev.kestrel.safety.PlayServices
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTree

/**
 * What may be in front when the run looks at the screen (design D16, D20 and D24; design 10.2 of sub-project 7): Kestrel's
 * own screen, which the run waits for; another agent-free app, which ends the task; a Play services screen, which is
 * handed over; and an app that stopped, which is closed with back and named in the next result. It also tells whether a
 * screen may be captured.
 */
internal class FrontCheck(private val run: TaskRun) {
    // An app stopped and Kestrel went back; the next result says so.
    private var appStopped = false

    /** Whether an app stopped since the last call, which the next result names; the note is taken once. */
    fun takeAppStopped(): Boolean = appStopped.also { appStopped = false }

    /**
     * Whether Kestrel may capture [snapshot] (D16, D20): never while a Play services window is on screen or an agent-free
     * app is in front. The eyes refuse the recent-apps and home screens themselves.
     */
    fun capturable(snapshot: ScreenSnapshot): Boolean {
        if (snapshot.windows.any { it.packageName == AgentFreeApps.PLAY_SERVICES }) return false
        val front = ScreenTree.frontWindow(snapshot)?.packageName ?: return false
        return !run.settings.agentFree.isAgentFree(front)
    }

    /**
     * Checks the app in front of [snapshot] before it is observed (D16, D24): Kestrel's own screen is waited for through
     * [pastKestrel]; another agent-free app ends the task; a Play services screen is handed over; a crashed app is closed
     * with back once the pause gate lets the run act, as every action does (plan 7E). Returns the snapshot to observe.
     */
    suspend fun checked(snapshot: ScreenSnapshot): ScreenSnapshot {
        var current = snapshot
        var checks = 0
        while (checks < CHECKS) {
            current = pastKestrel(current) { run.fresh() }
            val front = ScreenTree.frontWindow(current)?.packageName
            when {
                run.settings.agentFree.isAgentFree(front) -> throw Ended(EndReason.AGENT_FREE, detail = run.label(front) ?: front)
                PlayServices.handsOver(current) -> run.handOverOnly(HandOver.of(HandOverKind.PLAY_SERVICES))
                crashed(current) -> {
                    // Back acts on the screen, so it passes the pause gate first (D2; plan 7E). A gate that held the run
                    // is no check: the screen, which may have changed meanwhile, is read again, and every check is kept.
                    if (run.gate()) {
                        current = run.fresh()
                        continue
                    }
                    run.ports.screen.act(ScreenAction.Global(GlobalKind.BACK))
                    appStopped = true
                }
                else -> return current
            }
            checks++
            current = run.fresh()
        }
        // The screen after the last round is checked too: it is never observed while it shows one of these.
        current = pastKestrel(current) { run.fresh() }
        val front = ScreenTree.frontWindow(current)?.packageName
        if (run.settings.agentFree.isAgentFree(front)) throw Ended(EndReason.AGENT_FREE, detail = run.label(front) ?: front)
        if (PlayServices.handsOver(current) || crashed(current)) throw Ended(EndReason.STUCK)
        return current
    }

    /**
     * The screen behind Kestrel's own (design 10.2 of sub-project 7), the one rule for every look at the screen: [snapshot]
     * when Kestrel's own screen is not in front of it; otherwise the owner port waits until that screen has left, a wait
     * that does not count as work, and [read] reads the screen again, at most [CHECKS] times, since the screen may come
     * back. The task ends as interrupted when Kestrel's own screen stays through a wait or is still in front after the last
     * one. A null screen, which [read] may give, is returned as it is.
     */
    suspend fun <S : ScreenSnapshot?> pastKestrel(snapshot: S, read: suspend () -> S): S {
        var current = snapshot
        repeat(CHECKS) {
            if (current?.let(AgentFreeApps::kestrelInFront) != true) return current
            if (!run.limits.waiting { run.ports.owner.leaveKestrel() }) throw kestrelStayed()
            current = read()
        }
        if (current?.let(AgentFreeApps::kestrelInFront) == true) throw kestrelStayed()
        return current
    }

    private fun kestrelStayed(): Ended = Ended(EndReason.INTERRUPTED, interruption = Interruption.KESTREL_SCREEN)

    private fun crashed(snapshot: ScreenSnapshot): Boolean {
        val system = snapshot.windows.filter { it.packageName == ANDROID }.map { it.id }.toSet()
        val texts = snapshot.windows.filter { it.id in system }.mapNotNull { it.title } +
            snapshot.elements.filter { it.windowId in system }.mapNotNull { it.label }
        return texts.any { CRASHED.containsMatchIn(it) }
    }

    override fun toString(): String = "FrontCheck(appStopped=$appStopped)"

    private companion object {
        const val CHECKS = 3
        const val ANDROID = "android"
        val CRASHED = Regex("""(isn't responding|keeps stopping)$""")
    }
}
