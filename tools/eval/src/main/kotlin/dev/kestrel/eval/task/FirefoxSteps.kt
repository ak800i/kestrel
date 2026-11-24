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
    fun openTray(scope: RunScope): Boolean = start(scope) && AppTaps(scope, D3Tasks.FIREFOX).run(listOf(COUNTER))

    /**
     * Starts Firefox from its launcher and waits until its tab counter shows, at most [START] s; whether it showed.
     * Firefox's first run asks Android to make it the default browser (the A71's Firefox does, in the permission
     * controller's RequestRoleActivity), which only the owner may answer, so the wait ends there without an answer and
     * [RunScope.notes] records [FIRST_RUN].
     */
    fun start(scope: RunScope): Boolean {
        scope.device.idempotent("monkey -p ${D3Tasks.FIREFOX} -c android.intent.category.LAUNCHER 1")
        for (second in 1..START) {
            scope.clock.sleep(1.seconds)
            if (COUNTER.any { counted(scope, it) }) return true
            if (Dumps.resumedActivity(scope.device.read("dumpsys activity activities"))?.endsWith(ROLE_REQUEST) == true) {
                scope.notes[FIRST_RUN] = "yes"
                return false
            }
        }
        return false
    }

    /** Why a Firefox task is untestable when its tab counter did not show: its first run, or [otherwise]. */
    fun untestable(scope: RunScope, otherwise: String): SetupResult.Untestable =
        SetupResult.Untestable(if (scope.notes[FIRST_RUN] != null) FIRST_RUN_WHY else otherwise)

    /**
     * Stops Firefox, and after the run saw Firefox's first-run question, closes the Firefox task that still holds it after
     * the stop, as a swipe in Recents would, without an answer; Firefox's other tasks stay.
     */
    fun stop(scope: RunScope) {
        scope.device.idempotent("am force-stop ${D3Tasks.FIREFOX}")
        if (scope.notes[FIRST_RUN] == null) return
        for (task in Dumps.rootTasksAsking(scope.device.read("am stack list"), D3Tasks.FIREFOX, ROLE_REQUEST)) scope.device.idempotent("am stack remove $task")
        check(Dumps.rootTasksAsking(scope.device.read("am stack list"), D3Tasks.FIREFOX, ROLE_REQUEST).isEmpty()) { "Firefox's question about the default browser is still open." }
    }

    /** The permission controller's activity that asks the owner to give an app a role, such as the default browser. */
    const val ROLE_REQUEST = ".role.ui.RequestRoleActivity"

    /** The key of [RunScope.notes] that says Firefox showed its first run's question. */
    const val FIRST_RUN = "firefox-first-run"
    const val FIRST_RUN_WHY = "Firefox asks to be the default browser, a question of its first run that only the owner may answer"

    private fun counted(scope: RunScope, candidate: TapCandidate): Boolean =
        scope.hooks.screenHas(packageName = D3Tasks.FIREFOX, label = candidate.label, contains = candidate.contains, className = candidate.className, state = candidate.state)
            .int("count") >= 1

    private const val START = 15

    /**
     * The tab counter's label with a number of tabs, in the words that screen-has compares: kestrel-api37's "Non-private
     * Tabs Open: 76.", and older ones' "76 open tabs." and "1 open tab.". Each form's first entry tells which one shows.
     */
    private val COUNTS: List<Pair<String, (Int) -> String>> = listOf(
        "Tabs Open" to { tabs -> "Tabs Open $tabs" },
        "open tabs" to { tabs -> "$tabs open tabs" },
        "open tab" to { tabs -> "$tabs open tab" },
    )
    private const val MOST_TABS = 500

    /**
     * The number of tabs that the tab counter shows, or null when no tab counter shows. A counter with 0 tabs, or with
     * none of [COUNTS]' forms with a number up to [MOST_TABS], such as the empty counter of the A71's fresh Firefox,
     * which has no tabs, counts as 0. screen-has answers no text, so this asks for each number in turn.
     */
    fun tabs(scope: RunScope): Int? {
        if (COUNTER.none { counted(scope, it) }) return null
        val form = COUNTS.firstOrNull { shows(scope, it.first) }?.second ?: return 0
        return (0..MOST_TABS).firstOrNull { shows(scope, form(it)) } ?: 0
    }

    /** Whether the tab counter shows [tabs] tabs; for 0, also an empty counter. */
    fun showsTabs(scope: RunScope, tabs: Int): Boolean = COUNTS.any { shows(scope, it.second(tabs)) } || tabs == 0 && tabs(scope) == 0

    /**
     * Whether Firefox counts [tabs] non-private tabs, "Non-private Tabs Open: 1.", in its toolbar, on its homepage or in
     * its tabs tray. Unlike [showsTabs], it never reads the tray's count of private tabs, "Private Tabs Open: 0.".
     */
    fun showsNonPrivateTabs(scope: RunScope, tabs: Int): Boolean = shows(scope, "$NON_PRIVATE $tabs")

    private const val NON_PRIVATE = "Non-private Tabs Open"

    fun shows(scope: RunScope, text: String): Boolean = scope.hooks.screenHas(packageName = D3Tasks.FIREFOX, contains = text).int("count") >= 1

    /**
     * Closes the tab that the rehearsal's open made: none when Firefox still has the [before] tabs that it had before the
     * open, as when it selected a tab of the page that it had already; else Firefox's selected tab, with the "Close tab" of
     * the tab counter's menu, only while Firefox is in front, its address bar shows [address] and it has exactly one tab
     * more, and checks that Firefox said "Tab closed". It fails, naming what is left, otherwise.
     */
    fun closeOwnTab(scope: RunScope, address: String, before: Int) {
        if (Dumps.resumedPackage(scope.device.read("dumpsys activity activities")) != D3Tasks.FIREFOX) start(scope)
        if (showsTabs(scope, before)) return
        check(showsTabs(scope, before + 1)) { "Firefox does not have the one tab more that the rehearsal's page would have opened, so no tab was closed." }
        check(shows(scope, address)) { "Firefox's selected tab does not show $address, so no tab was closed." }
        val menu = COUNTER.map { it.copy(long = true) }
        check(AppTaps(scope, D3Tasks.FIREFOX).run(listOf(menu, listOf(TapCandidate(label = CLOSE))))) { "Firefox's Close tab was not tapped." }
        check(scope.hooks.screenHas(packageName = D3Tasks.FIREFOX, label = CLOSED).int("count") >= 1) { "Firefox did not say that it closed the tab." }
    }
}

/**
 * firefox-url's tab (design D8 and D9 of sub-project 6). Before the run, Firefox's tabs tray must show no tab of [page],
 * so that the restore closes only the run's own; the setup counts Firefox's tabs, and Firefox is stopped again. After the
 * run, the restore opens the tray and, when it shows a tab of the page and Firefox has exactly one tab more than before,
 * closes the one tab titled [title] by its close button, and checks that Firefox has as many tabs as before. It does not
 * look for the page outside the tray: once its last tab closed, the A71's Firefox shows its homepage, whose top sites
 * hold the page from earlier runs. [title] is the title that GitHub gives the repository's page, which holds the
 * repository's description.
 */
data class FirefoxTab(val page: String = "ak800i", val title: String = GITHUB) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        scope.restores.push("Firefox stopped", "Close Firefox, also in Recents.") { Firefox.stop(scope) }
        var tabs: Int? = null
        val opened = scope.restores.change("Firefox's tabs tray opened", "Close Firefox.") {
            Firefox.start(scope) && Firefox.tabs(scope).also { tabs = it } != null && AppTaps(scope, D3Tasks.FIREFOX).run(listOf(Firefox.COUNTER))
        }
        val before = opened && Firefox.shows(scope, page)
        scope.restores.change("Firefox stopped again", "Close Firefox, also in Recents.") { Firefox.stop(scope) }
        val counted = tabs
        if (!opened || counted == null) return Firefox.untestable(scope, "Firefox's tabs tray did not open")
        if (before) return SetupResult.Untestable("Firefox already has a tab of $page, which the run would not tell from its own")
        scope.restores.push("the run's tab in Firefox", "Firefox: close the tab of $page.") {
            check(Firefox.openTray(scope)) { "Firefox's tabs tray did not open." }
            if (!Firefox.shows(scope, page)) return@push
            check(Firefox.showsNonPrivateTabs(scope, counted + 1)) { "Firefox does not have the one tab more that the run would have opened, so no tab was closed." }
            check(AppTaps(scope, D3Tasks.FIREFOX).run(listOf(Firefox.closeTaps(title)))) { "The close button of the run's tab was not tapped." }
            check(Firefox.showsNonPrivateTabs(scope, counted)) { "Firefox does not have the $counted tabs that it had before the run, so the run's tab may still be open." }
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
 * open): Firefox started and its tabs counted, [uri] opened by intent, and after the check the tab that the open made
 * closed through the tab counter's menu ([Firefox.closeOwnTab]), only when Firefox has one tab more than before the open
 * and its address bar shows [address], before Firefox is stopped. So a tab of the same page that an earlier run left,
 * which Firefox may select instead of opening a new one, stays, and so do the owner's other tabs, because they are not
 * selected. With no tab counter on the screen the task is untestable; an empty counter is 0 tabs.
 */
data class FirefoxNewTab(val uri: String, val address: String, val settle: Duration = 10.seconds) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        // Set inside the change, which the shutdown hook awaits before it runs the restore: a page that was never asked for closes no tab.
        val asked = AtomicBoolean(false)
        scope.restores.push("Firefox stopped", "Close Firefox, also in Recents.") { Firefox.stop(scope) }
        scope.restores.change("Firefox started", "Close Firefox.") { Firefox.start(scope) }
        val tabs = Firefox.tabs(scope) ?: return Firefox.untestable(scope, "Firefox's tab counter did not show")
        scope.restores.push("the rehearsal's tab in Firefox", "Firefox: close the tab of $address.") { if (asked.get()) Firefox.closeOwnTab(scope, address, tabs) }
        // A lost answer needs no read-back here: the task's checks read the screen next, and the restore reads the address.
        scope.restores.change("a page opened in Firefox", "Close Firefox.") {
            asked.set(true)
            scope.device.once("am start -W -a android.intent.action.VIEW -d '$uri' -p ${D3Tasks.FIREFOX}")
        }
        scope.clock.sleep(settle)
        return SetupResult.Ready
    }
}
