package dev.kestrel.screen.model

/**
 * An app's own rule for the title of its screens (design D8 of sub-project 5): the view-ID names of the header's title
 * ([titleIds], the part after `:id/`, in order of preference), or for an app without view IDs the top fraction of the
 * window ([headerBand]) in which the first text outside every scrollable container is the header.
 */
data class TitleRule(val packageName: String, val titleIds: List<String> = emptyList(), val headerBand: Double? = null)

/**
 * The apps' title rules, rule 1 of [ScreenTitle]. Plan 5B adds the emulator's entries from the trees that its device
 * tests record, and the controller the A71's after the owner's first sitting (design D8 and D30). Viber gets none until
 * a tree of the real Viber app is recorded (the owner's answer to Q1).
 */
object ScreenTitles {
    val RULES: List<TitleRule> = listOf(
        // Telegram draws its chat header without view IDs (research r63): the title is the first text of the header.
        TitleRule("org.telegram.messenger", headerBand = 0.12),
        TitleRule("org.telegram.messenger.web", headerBand = 0.12),
        // :testapp's messenger (plan 5B) names its chat header `chat_title`.
        TitleRule("dev.kestrel.testapp", titleIds = listOf("chat_title")),
        // sub-project 5B: entries
    )

    fun ruleFor(packageName: String?): TitleRule? = RULES.firstOrNull { it.packageName == packageName }
}
