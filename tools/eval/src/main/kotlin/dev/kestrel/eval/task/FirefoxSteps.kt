package dev.kestrel.eval.task

import dev.kestrel.eval.device.Dumps
import java.util.concurrent.atomic.AtomicBoolean
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
    /**
     * The tab counter: kestrel-api37's "Non-private Tabs Open: 3. Tap to switch tabs.", a clickable element with an inner
     * copy of its label, which counts once as the one clickable element; older ones' "3 open tabs. Tap to switch tabs.",
     * "1 open tab. …", or a button "Tabs".
     */
    val COUNTER = listOf(
        TapCandidate(contains = "Non-private Tabs Open", state = "clickable"),
        TapCandidate(contains = "open tabs"),
        TapCandidate(contains = "open tab"),
        TapCandidate(label = "Tabs"),
    )

    /** The item of the tab counter's menu (a long press) that closes the selected tab, and what Firefox says then. */
    const val CLOSE = "Close tab"
    const val CLOSED = "Tab closed"

    /**
     * The close button of the one tab titled [title] in the tray, by its exact label "Close tab <title>": the tab's card
     * holds that text too, and screen-tap reaches the card first by a part of a label.
     */
    fun closeTaps(title: String): List<TapCandidate> = listOf(TapCandidate(label = "$CLOSE $title"))

    /** Starts Firefox and opens its tabs tray once the tab counter shows, within [START] s; whether the tab counter was tapped. */
    fun openTray(scope: RunScope): Boolean {
        start(scope)
        return AppTaps(scope, D3Tasks.FIREFOX).run(listOf(COUNTER))
    }

    /** Starts Firefox from its launcher and waits until its tab counter shows, at most [START] s. */
    private fun start(scope: RunScope) {
        scope.device.idempotent("monkey -p ${D3Tasks.FIREFOX} -c android.intent.category.LAUNCHER 1")
        for (second in 1..START) {
            scope.clock.sleep(1.seconds)
            if (COUNTER.any { counted(scope, it) }) return
        }
    }

    private fun counted(scope: RunScope, candidate: TapCandidate): Boolean =
        scope.hooks.screenHas(packageName = D3Tasks.FIREFOX, label = candidate.label, contains = candidate.contains, className = candidate.className, state = candidate.state)
            .int("count") >= 1

    private const val START = 15

    fun shows(scope: RunScope, text: String): Boolean = scope.hooks.screenHas(packageName = D3Tasks.FIREFOX, contains = text).int("count") >= 1

    /**
     * Closes Firefox's selected tab with the "Close tab" of the tab counter's menu, only while Firefox is in front and its
     * address bar shows [address], and checks that Firefox said "Tab closed"; it fails, naming what is left, otherwise.
     */
    fun closeSelected(scope: RunScope, address: String) {
        if (Dumps.resumedPackage(scope.device.read("dumpsys activity activities")) != D3Tasks.FIREFOX) start(scope)
        check(shows(scope, address)) { "Firefox's selected tab does not show $address, so no tab was closed." }
        val menu = COUNTER.map { it.copy(long = true) }
        check(AppTaps(scope, D3Tasks.FIREFOX).run(listOf(menu, listOf(TapCandidate(label = CLOSE))))) { "Firefox's Close tab was not tapped." }
        check(scope.hooks.screenHas(packageName = D3Tasks.FIREFOX, label = CLOSED).int("count") >= 1) { "Firefox did not say that it closed the tab." }
    }
}

/**
 * firefox-url's tab (design D8 and D9 of sub-project 6). Before the run, Firefox's tabs tray must show no tab of [page],
 * so that the restore closes only the run's own; Firefox is stopped again. After the run, the restore opens the tray,
 * closes the one tab titled [title] by its close button, and checks that the tray no longer shows the page. [title] is
 * the title that GitHub gives the repository's page, which holds the repository's description.
 */
data class FirefoxTab(val page: String = "ak800i", val title: String = GITHUB) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        scope.restores.push("Firefox stopped", "Close Firefox.") { scope.device.idempotent("am force-stop ${D3Tasks.FIREFOX}") }
        val opened = scope.restores.change("Firefox's tabs tray opened", "Close Firefox.") { Firefox.openTray(scope) }
        val before = opened && Firefox.shows(scope, page)
        scope.restores.change("Firefox stopped again", "Close Firefox.") { scope.device.idempotent("am force-stop ${D3Tasks.FIREFOX}") }
        if (!opened) return SetupResult.Untestable("Firefox's tabs tray did not open")
        if (before) return SetupResult.Untestable("Firefox already has a tab of $page, which the run would not tell from its own")
        scope.restores.push("the run's tab in Firefox", "Firefox: close the tab of $page.") {
            check(Firefox.openTray(scope)) { "Firefox's tabs tray did not open." }
            if (Firefox.shows(scope, page)) AppTaps(scope, D3Tasks.FIREFOX).run(listOf(Firefox.closeTaps(title)))
            check(!Firefox.shows(scope, page)) { "The run's tab is still open in Firefox." }
        }
        return SetupResult.Ready
    }

    companion object {
        const val GITHUB = "GitHub - ak800i/kestrel: A private, voice-first assistant for Android that performs tasks through the accessibility " +
            "service. Only the configured LLM provider receives data. · GitHub"
    }
}

/**
 * A rehearsal's page in a new tab of Firefox, which Firefox selects (firefox-wiki's, which plan 6A's rehearsal left
 * open): [uri] opened by intent, and after the check the selected tab closed through the tab counter's menu
 * ([Firefox.closeSelected]), only while the address bar shows [address], before Firefox is stopped. The owner's tabs of the
 * same page stay, because they are not selected.
 */
data class FirefoxNewTab(val uri: String, val address: String, val settle: Duration = 10.seconds) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        // Set inside the change, which the shutdown hook awaits before it runs the restore: a page that was never asked for closes no tab.
        val asked = AtomicBoolean(false)
        scope.restores.push("Firefox stopped", "Close Firefox.") { scope.device.idempotent("am force-stop ${D3Tasks.FIREFOX}") }
        scope.restores.push("the rehearsal's tab in Firefox", "Firefox: close the tab of $address.") { if (asked.get()) Firefox.closeSelected(scope, address) }
        // A lost answer needs no read-back here: the task's checks read the screen next, and the restore reads the address.
        scope.restores.change("a page opened in Firefox", "Close Firefox.") {
            asked.set(true)
            scope.device.once("am start -W -a android.intent.action.VIEW -d '$uri' -p ${D3Tasks.FIREFOX}")
        }
        scope.clock.sleep(settle)
        return SetupResult.Ready
    }
}
