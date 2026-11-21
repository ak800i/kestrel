package dev.kestrel.agent

import dev.kestrel.agent.port.AgentClock
import dev.kestrel.agent.port.AppsPort
import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.EyesPort
import dev.kestrel.agent.port.MemoryStore
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.ScreenPort
import dev.kestrel.agent.port.TaskLog

/** The ports through which the agent reaches the rest of Kestrel (design D1); `:app` binds them, the JVM tests fake them. */
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
) {
    override fun toString(): String = "AgentPorts"
}
