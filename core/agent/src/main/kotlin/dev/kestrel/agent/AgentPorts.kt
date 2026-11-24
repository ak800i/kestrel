package dev.kestrel.agent

import dev.kestrel.agent.goal.GoalPort
import dev.kestrel.agent.goal.HistoryPort
import dev.kestrel.agent.port.AgentClock
import dev.kestrel.agent.port.AppsPort
import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.EyesPort
import dev.kestrel.agent.port.MemoryStore
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.ScreenPort
import dev.kestrel.agent.port.TaskLog

/**
 * The ports through which the agent reaches the rest of Kestrel (design D1); `:app` binds them, the JVM tests fake them.
 * [goal] is the goal around the run and [history] the search over past conversations (plan 7A; design 5.4 of
 * sub-project 7), which the caller chooses.
 */
class AgentPorts(
    val screen: ScreenPort,
    val eyes: EyesPort,
    val data: DataPort,
    val owner: OwnerPort,
    val model: ModelPort,
    val apps: AppsPort,
    val memory: MemoryStore,
    val log: TaskLog,
    val clock: AgentClock,
    val goal: GoalPort,
    val history: HistoryPort,
) {
    /** These ports with [owner] in the owner's place: a run's own owner port, which traces its dialogue (plan 7A). */
    internal fun withOwner(owner: OwnerPort): AgentPorts = AgentPorts(screen, eyes, data, owner, model, apps, memory, log, clock, goal, history)

    override fun toString(): String = "AgentPorts"
}
