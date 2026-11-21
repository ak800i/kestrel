package dev.kestrel.agent.observe

import dev.kestrel.screen.model.ScreenElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which parts of SystemUI's windows an observation may show (design D9 of sub-project 5): a Quick Settings tile (its
 * label and state) stays, a notification (a heads-up or a row of the shade) becomes one count in the header line
 * "Notifications: hidden (…)", and everything else of SystemUI, the status bar's notification icons included, is
 * dropped. The lists are data, in the resource `systemui-parts.json`: one entry per source (the AOSP defaults, plan 5B's
 * emulator entries, the controller's entries for the A71), which [parse] merges. View IDs are matched by their name
 * after `:id/`.
 */
class SystemUiParts(
    val tileIds: Set<String>,
    val tileClasses: Set<String>,
    val notificationIds: Set<String>,
    val notificationClasses: Set<String>,
) {
    fun isTile(element: ScreenElement): Boolean = matches(element, tileIds, tileClasses)

    fun isNotification(element: ScreenElement): Boolean = matches(element, notificationIds, notificationClasses)

    private fun matches(element: ScreenElement, ids: Set<String>, classes: Set<String>): Boolean {
        val id = element.viewId?.substringAfter(":id/")
        return (id != null && id in ids) || (element.className != null && element.className in classes)
    }

    override fun toString(): String =
        "SystemUiParts(tileIds=${tileIds.size}, tileClasses=${tileClasses.size}, notificationIds=${notificationIds.size}, notificationClasses=${notificationClasses.size})"

    companion object {
        const val SYSTEM_UI = "com.android.systemui"

        /** The union of every source in `{"sources": {"<name>": {"tileIds": […], …}}}`; unknown keys are ignored. */
        fun parse(json: String): SystemUiParts {
            val sources = Json.parseToJsonElement(json).let { it as JsonObject }["sources"] as? JsonObject ?: JsonObject(emptyMap())
            fun all(name: String): Set<String> = sources.values.flatMap { source ->
                ((source as? JsonObject)?.get(name) as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            }.toSet()
            return SystemUiParts(all("tileIds"), all("tileClasses"), all("notificationIds"), all("notificationClasses"))
        }

        val DEFAULT: SystemUiParts by lazy {
            val stream = SystemUiParts::class.java.getResourceAsStream("/systemui-parts.json") ?: error("systemui-parts.json is missing")
            parse(stream.bufferedReader().use { it.readText() })
        }
    }
}
