package dev.kestrel.agent.testing

import dev.kestrel.screen.model.ScreenJson
import dev.kestrel.screen.model.ScreenSnapshot

/** The fixture screens of the agent's tests, JSON files in `src/testFixtures/resources/screens/` (design D27). */
object Fixtures {
    fun screen(name: String): ScreenSnapshot {
        val stream = Fixtures::class.java.getResourceAsStream("/screens/$name.json") ?: error("No fixture screen $name")
        return ScreenJson.decode(stream.bufferedReader().use { it.readText() })
    }
}
