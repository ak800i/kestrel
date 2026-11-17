package dev.kestrel.commands

/** The text commands of the command list. Every example must parse to a command (`CommandHelpTest`). */
internal object HelpText {
    val groups: List<HelpGroup> = listOf(
        HelpGroup(
            "Dictation (say the punctuation: comma, period, question mark)",
            listOf("start editing", "stop editing", "type see you soon", "new line"),
        ),
        HelpGroup(
            "Editing text",
            listOf("undo", "redo", "insert very before nice", "replace Monday with Tuesday", "capitalize paris", "uppercase nasa", "format email"),
        ),
        HelpGroup("Deleting text", listOf("delete", "delete all", "delete tomorrow", "delete three words", "delete from hello to world")),
        HelpGroup("Moving the cursor", listOf("go to the end", "move after tomorrow", "left three characters", "right two words")),
        HelpGroup("Selecting text", listOf("select all", "select tomorrow", "select the next two words", "unselect")),
        HelpGroup("Clipboard and keyboard", listOf("copy", "cut", "paste", "show keyboard", "hide keyboard")),
        HelpGroup("Passwords and search", listOf("show password", "search for coffee near me")),
    )
}
