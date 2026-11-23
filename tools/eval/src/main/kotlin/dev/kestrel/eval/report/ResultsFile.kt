package dev.kestrel.eval.report

import dev.kestrel.eval.device.Clock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.format.DateTimeFormatter

/**
 * A results file in the form of `Add-SessionResult` (design D6 of sub-project 6): `<yyyy-MM-dd HH:mm:ss>\t<step>\t<text>`
 * per line, in UTF-8 without a BOM, with its folder created; scripts\a71-agent.ps1 reads the run lines back from it.
 */
class ResultsFile(val path: Path, private val clock: Clock) {
    fun append(step: String, text: String) = synchronized(this) {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(path, "${clock.now().format(TIME)}\t$step\t$text\n", Charsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        Unit
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
