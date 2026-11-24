package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import kotlin.time.Duration.Companion.seconds

/**
 * The suite's app tasks (design D8 of sub-project 6), on both devices. firefox-url's new tab is closed after the run;
 * YouTube's and Maps' searches stay in the secondary account's history, with no removal step (the owner's answer 1); the
 * newest photo is a test card that leaves with its folder. A rehearsal opens the page, the search or the photo by intent.
 * YouTube's question about notifications and Maps' about location are dismissed before the run without an answer
 * ([DismissPermissionPrompt]).
 */
object AppTasks {
    /** Google's YouTube, kestrel-api37's, and ReVanced's, the A71's (design Facts). */
    val YOUTUBE = listOf("com.google.android.youtube", "app.revanced.android.youtube")
    const val MAPS = "com.google.android.apps.maps"
    private val BOTH = setOf(DeviceKind.EMULATOR, DeviceKind.A71)

    val FIREFOX_URL = Task(
        name = "firefox-url",
        kind = TaskKind.SUITE,
        request = Request("open github.com/ak800i/kestrel in Firefox"),
        devices = BOTH,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(Installed(listOf(D3Tasks.FIREFOX)), ForceStop(D3Tasks.FIREFOX), FirefoxTab()),
        check = listOf(Foreground(setOf(D3Tasks.FIREFOX)), ScreenHas(packageName = D3Tasks.FIREFOX, contains = "ak800i")),
        dryRun = DryRun("firefox-url.json"),
        rehearsal = Rehearsal.Steps(listOf(OpenUri("https://github.com/ak800i/kestrel", D3Tasks.FIREFOX, settle = 10.seconds))),
    )

    val YOUTUBE_SEARCH = Task(
        name = "youtube-search",
        kind = TaskKind.SUITE,
        request = Request("search YouTube for kestrel hovering"),
        devices = BOTH,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(Installed(YOUTUBE)) + YOUTUBE.map { ForceStop(it) } + DismissPermissionPrompt(YOUTUBE, DismissPermissionPrompt.NOTIFICATIONS, "https://www.youtube.com/"),
        check = listOf(Foreground(YOUTUBE.toSet()), ScreenHas(contains = "kestrel hovering")),
        dryRun = DryRun("youtube-search.json"),
        rehearsal = Rehearsal.Steps(listOf(OpenUriIn("https://www.youtube.com/results?search_query=kestrel+hovering", YOUTUBE, settle = 10.seconds))),
    )

    val MAPS_PLACE = Task(
        name = "maps-place",
        kind = TaskKind.SUITE,
        request = Request("show Kalemegdan on Google Maps"),
        devices = BOTH,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(Installed(listOf(MAPS)), ForceStop(MAPS), DismissPermissionPrompt(listOf(MAPS), DismissPermissionPrompt.LOCATION, "geo:0,0")),
        check = listOf(Foreground(setOf(MAPS)), ScreenHas(packageName = MAPS, contains = "Kalemegdan")),
        dryRun = DryRun("maps-place.json"),
        rehearsal = Rehearsal.Steps(listOf(OpenUri("geo:0,0?q=Kalemegdan", MAPS, settle = 10.seconds))),
    )

    val PHOTO_LATEST = Task(
        name = "photo-latest",
        kind = TaskKind.SUITE,
        request = Request("show me my latest photo"),
        devices = BOTH,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(HomeScreen, Installed(Gallery.PACKAGES), ForceStop(Gallery.SAMSUNG), ForceStop(Gallery.PHOTOS), NewestPhoto, Grant("dev.kestrel", Grant.MEDIA)),
        check = listOf(PhotoViewer),
        dryRun = DryRun("photo-latest.json"),
        rehearsal = Rehearsal.Steps(listOf(ViewNewestPhoto)),
    )

    val ALL = listOf(FIREFOX_URL, YOUTUBE_SEARCH, MAPS_PLACE, PHOTO_LATEST)
}
