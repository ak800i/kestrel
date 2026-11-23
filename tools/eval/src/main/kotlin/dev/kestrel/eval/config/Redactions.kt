package dev.kestrel.eval.config

/**
 * What of a text may reach the console (design D2 of sub-project 6; task-check.ps1's `Format-TaskFailure` and
 * a71-agent.ps1's `Format-Failure`): its first line, with every e-mail address and `mailto:` URI, and every value of the
 * local configuration, left out. A device command that failed, such as the mail setup's `mailto:`, names the owner's
 * address in its error.
 */
class Redactions(private val config: LocalConfig) {
    fun forConsole(text: String): String {
        var line = text.lineSequence().firstOrNull().orEmpty()
        for (value in config.allValues) {
            if (value.length >= 3) line = line.replace(value, "<value>", ignoreCase = true)
        }
        return line.replace(MAILTO, "mailto:<address>").replace(ADDRESS, "<address>")
    }

    private companion object {
        val MAILTO = Regex("mailto:[^\\s']+")
        val ADDRESS = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+")
    }
}
