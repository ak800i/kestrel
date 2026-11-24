package dev.kestrel.eval.task

import dev.kestrel.eval.device.Dumps
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One of [packages] is installed and enabled (`pm list packages -e`), or the task is untestable on the device; it changes nothing. */
data class Installed(val packages: List<String>) : Setup {
    override fun apply(scope: RunScope): SetupResult =
        if (packages.any { enabled(scope, it) }) SetupResult.Ready else SetupResult.Untestable("${packages.joinToString(" or ")} is not installed")

    companion object {
        fun enabled(scope: RunScope, packageName: String): Boolean = Dumps.packageListed(scope.device.read("pm list packages -e $packageName"), packageName)
    }
}

/** [OpenUri] in the first of [packages] that is installed and enabled, such as Google's YouTube or ReVanced's on the A71. */
data class OpenUriIn(val uri: String, val packages: List<String>, val settle: Duration = 5.seconds) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val app = packages.firstOrNull { Installed.enabled(scope, it) } ?: return SetupResult.Untestable("${packages.joinToString(" or ")} is not installed")
        return OpenUri(uri, app, settle).apply(scope)
    }
}

/**
 * Firefox's tabs tray (design D9 of sub-project 6): Firefox started from its launcher, and the tray opened with the tab
 * counter. The labels are candidates that Task 10 of plan 6B confirms on kestrel-api37 and Task 12 on the A71.
 */
object Firefox {
    /** The tab counter: "3 open tabs. Tap to switch tabs.", "1 open tab. …", or a button "Tabs". */
    val COUNTER = listOf(TapCandidate(contains = "open tabs"), TapCandidate(contains = "open tab"), TapCandidate(label = "Tabs"))

    /** The close button of the one tab of [page] in the tray, "Close tab <title>". */
    fun closeTaps(page: String): List<TapCandidate> = listOf(TapCandidate(contains = "Close tab GitHub $page"), TapCandidate(contains = "Close tab $page"))

    /** Starts Firefox and opens its tabs tray; whether the tab counter was tapped. */
    fun openTray(scope: RunScope): Boolean {
        scope.device.idempotent("monkey -p ${D3Tasks.FIREFOX} -c android.intent.category.LAUNCHER 1")
        scope.clock.sleep(4.seconds)
        return AppTaps(scope, D3Tasks.FIREFOX).run(listOf(COUNTER))
    }

    fun shows(scope: RunScope, text: String): Boolean = scope.hooks.screenHas(packageName = D3Tasks.FIREFOX, contains = text).int("count") >= 1
}

/**
 * firefox-url's tab (design D8 and D9 of sub-project 6). Before the run, Firefox's tabs tray must show no tab of [page],
 * so that the restore closes only the run's own; Firefox is stopped again. After the run, the restore opens the tray,
 * closes the one tab whose close button names the page, and checks that the tray no longer shows the page.
 */
data class FirefoxTab(val page: String = "ak800i") : Setup {
    override fun apply(scope: RunScope): SetupResult {
        scope.restores.push("Firefox stopped", "Close Firefox.") { scope.device.idempotent("am force-stop ${D3Tasks.FIREFOX}") }
        val opened = Firefox.openTray(scope)
        val before = opened && Firefox.shows(scope, page)
        scope.device.idempotent("am force-stop ${D3Tasks.FIREFOX}")
        if (!opened) return SetupResult.Untestable("Firefox's tabs tray did not open")
        if (before) return SetupResult.Untestable("Firefox already has a tab of $page, which the run would not tell from its own")
        scope.restores.push("the run's tab in Firefox", "Firefox: close the tab of $page.") {
            check(Firefox.openTray(scope)) { "Firefox's tabs tray did not open." }
            if (Firefox.shows(scope, page)) AppTaps(scope, D3Tasks.FIREFOX).run(listOf(Firefox.closeTaps(page)))
            check(!Firefox.shows(scope, page)) { "The run's tab is still open in Firefox." }
        }
        return SetupResult.Ready
    }
}
