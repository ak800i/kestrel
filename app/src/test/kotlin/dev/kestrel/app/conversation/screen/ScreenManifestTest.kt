package dev.kestrel.app.conversation.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Design 12.2 of sub-project 7, over the build's merged manifest, which Gradle names in the system property
 * `kestrel.mergedManifest` (`MergedManifestArgument`): the conversation screen's only filter is the launcher's, so that
 * no intent carries words to it; no activity takes shared or processed text or a link; and no other activity is the
 * launcher's.
 */
class ScreenManifestTest {
    private val activities: List<Element> by lazy {
        val path = checkNotNull(System.getProperty(PROPERTY)) { "Run the test with Gradle, which names the merged manifest in $PROPERTY" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File(path))
        manifest.getElementsByTagName("activity").elements() + manifest.getElementsByTagName("activity-alias").elements()
    }

    @Test
    fun theConversationScreensOnlyFilterIsTheLaunchers() {
        val screen = activities.single { nameOf(it) == SCREEN }
        val filter = filtersOf(screen).single()

        assertEquals(listOf(MAIN), namesIn(filter, "action"))
        assertEquals(listOf(LAUNCHER), namesIn(filter, "category"))
        assertEquals(0, filter.getElementsByTagName("data").length)
    }

    @Test
    fun noActivityTakesSharedOrProcessedTextOrALinkAndNoOtherActivityIsTheLaunchers() {
        for (activity in activities) {
            val name = nameOf(activity)
            for (filter in filtersOf(activity)) {
                val taken = namesIn(filter, "action").filter { it in REFUSED }
                assertTrue("$name takes $taken", taken.isEmpty())
                if (name != SCREEN) assertTrue("$name is a launcher's activity", LAUNCHER !in namesIn(filter, "category"))
            }
        }
    }

    // The merger writes full class names; a name that starts with a dot is in the app's namespace.
    private fun nameOf(element: Element): String =
        element.getAttributeNS(ANDROID, "name").let { if (it.startsWith(".")) NAMESPACE + it else it }

    private fun filtersOf(activity: Element): List<Element> =
        activity.childNodes.let { nodes -> (0 until nodes.length).map(nodes::item) }.filterIsInstance<Element>().filter { it.tagName == "intent-filter" }

    private fun namesIn(filter: Element, tag: String): List<String> =
        filter.getElementsByTagName(tag).elements().map { it.getAttributeNS(ANDROID, "name") }

    private fun NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

    private companion object {
        const val PROPERTY = "kestrel.mergedManifest"
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val NAMESPACE = "dev.kestrel.app"
        const val SCREEN = "dev.kestrel.app.conversation.screen.ConversationActivity"
        const val MAIN = "android.intent.action.MAIN"
        const val LAUNCHER = "android.intent.category.LAUNCHER"

        /** Intents that carry words or a link from another app (design 12.2). */
        val REFUSED = setOf(
            "android.intent.action.SEND",
            "android.intent.action.SEND_MULTIPLE",
            "android.intent.action.PROCESS_TEXT",
            "android.intent.action.VIEW",
        )
    }
}
