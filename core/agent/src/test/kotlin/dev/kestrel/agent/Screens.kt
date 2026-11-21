package dev.kestrel.agent

import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.screen.model.ScreenSnapshot

/**
 * The fixture messenger's screens as the JVM tests build them (design D27 and D28): `:testapp`'s messenger runs as
 * `dev.kestrel.testapp`, whose chat header has the view ID `chat_title` (`ScreenTitles`' rule for `:testapp`).
 */
object Screens {
    const val MESSENGER = "dev.kestrel.testapp"
    const val MAIL = "com.google.android.gm"

    fun chats(vararg names: String = arrayOf("Ana Petrović", "Marko Marković")): ScreenSnapshot = ScreenBuilder.screen(MESSENGER, "Kestrel fixtures") {
        text("Chats", 100, viewId = "chat_title")
        val list = list("Chats", 300, 2200, viewId = "chat_list")
        names.forEachIndexed { index, name -> row(name, 320 + index * 140, list, viewId = "chat_row") }
    }

    fun chat(name: String, vararg messages: String, typed: String = "", call: Boolean = false): ScreenSnapshot =
        ScreenBuilder.screen(MESSENGER, "Kestrel fixtures") {
            icon("Navigate up", 100, viewId = "up")
            text(name, 100, viewId = "chat_title")
            if (call) button("Call", 100, viewId = "call", left = 900)
            val list = list("Messages", 300, 2000, viewId = "messages")
            messages.forEachIndexed { index, message -> text(message, 320 + index * 130, viewId = "bubble", parent = list) }
            field("Type a message…", typed, 2100, viewId = "compose")
            button("Send", 2250, viewId = "send", left = 900)
        }

    /** An e-mail being written; [copies] shows Cc and Bcc, else they are collapsed behind "Show Cc/Bcc". */
    fun email(copies: Boolean): ScreenSnapshot = ScreenBuilder.screen(MAIL, "Gmail") {
        icon("Send", 100, viewId = "send", left = 900, right = 1040)
        field("To", "ana@example.com", 300, viewId = "to")
        if (copies) {
            field("Cc", "boss@example.com", 420, viewId = "cc")
            field("Bcc", "", 540, viewId = "bcc")
        } else {
            icon("Show Cc/Bcc", 300, viewId = "add_cc_bcc", left = 900, right = 1040)
        }
        field("Subject", "Late", 700, viewId = "subject")
        field("Compose email", "I'm late", 850, viewId = "body")
    }

    fun settingsPage(title: String, switch: String, checked: Boolean): ScreenSnapshot = ScreenBuilder.screen("com.android.settings", "Settings") {
        text(title, 100, viewId = "action_bar_title")
        switch(switch, checked, 400, viewId = "switch_widget")
    }

    fun app(packageName: String, vararg texts: String): ScreenSnapshot = ScreenBuilder.screen(packageName) {
        texts.forEachIndexed { index, text -> text(text, 300 + index * 130) }
    }
}
