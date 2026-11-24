package dev.kestrel.eval.egress

import dev.kestrel.eval.report.EgressSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * One part of a results folder's egress check (design D11 of sub-project 6): the capture of one call of a series, named
 * by the call's [stamp], with its [summary] and its [text] once it was judged. A part without a summary is unknown, for
 * its [reason]: a capture that could not be judged, a series that ended before its capture was judged, or runs that were
 * never captured.
 */
@Serializable
data class EgressPart(val stamp: String, val summary: EgressSummary? = null, val text: String? = null, val reason: String? = null)

/**
 * `egress.json` and `egress.txt` of a results folder: the egress check of every call that made runs in it, so that a
 * suite that stopped and was resumed is judged on all of its runs. The folder passes only when every part was judged and
 * passed; an unknown part fails it. `egress.txt` is the one part's text, or each part's after its stamp and then the total.
 */
object EgressParts {
    const val FILE = "egress.json"
    const val TEXT = "egress.txt"

    fun read(folder: Path): List<EgressPart> =
        folder.resolve(FILE).takeIf { Files.isRegularFile(it) }?.let { JSON.decodeFromString(SERIALIZER, Files.readString(it)) }.orEmpty()

    /** Puts [part] in place of the part of its stamp, or after the others, and writes `egress.json` and `egress.txt` again. */
    fun put(folder: Path, part: EgressPart) {
        val parts = read(folder)
        val all = if (parts.any { it.stamp == part.stamp }) parts.map { if (it.stamp == part.stamp) part else it } else parts + part
        Files.createDirectories(folder)
        Files.writeString(folder.resolve(FILE), JSON.encodeToString(SERIALIZER, all) + "\n")
        Files.writeString(folder.resolve(TEXT), text(all))
    }

    /** The folder's result, or null when no call captured its runs. */
    fun summary(parts: List<EgressPart>): EgressSummary? {
        if (parts.isEmpty()) return null
        val judged = parts.mapNotNull { it.summary }
        val unknown = parts.size - judged.size
        return EgressSummary(unknown == 0 && judged.all { it.passed }, judged.sumOf { it.connections }, judged.sumOf { it.failures }, unknown)
    }

    private fun text(parts: List<EgressPart>): String {
        parts.singleOrNull()?.text?.let { return it }
        val total = summary(parts) ?: return ""
        return buildString {
            for (part in parts) {
                if (part.text != null) append("part ${part.stamp}\n").append(part.text) else append("part ${part.stamp} unknown: ${part.reason}\n")
            }
            append("result ${if (total.passed) "pass" else "fail"} connections=${total.connections} failures=${total.failures} unknown=${total.unknown}\n")
        }
    }

    private val JSON = Json { prettyPrint = true }
    private val SERIALIZER = ListSerializer(EgressPart.serializer())
}
