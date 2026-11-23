package dev.kestrel.eval.run

import dev.kestrel.eval.device.Processes
import dev.kestrel.eval.device.Refusal
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * Installs a missing build through `scripts\install.ps1`, the only install path that AGENTS.md allows (design D7 of
 * sub-project 6): Kestrel's debug build, or `:testapp` with `-TestApp`. install.ps1 runs Gradle, so the daemon is
 * stopped afterwards with `scripts\gradle.ps1 --stop`, as AGENTS.md asks.
 */
class Installer(private val root: Path, private val processes: Processes) {
    fun install(serial: String, testApp: Boolean) {
        val arguments = listOf("-Serial", serial) + if (testApp) listOf("-TestApp") else emptyList()
        val result = processes.run(powershell("install.ps1") + arguments, 30.minutes)
        processes.run(powershell("gradle.ps1") + "--stop", 5.minutes)
        if (result.exitCode != 0) throw Refusal("scripts\\install.ps1 ${arguments.joinToString(" ")} failed with exit code ${result.exitCode}.")
    }

    private fun powershell(script: String): List<String> =
        listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", root.resolve("scripts").resolve(script).toString())
}
