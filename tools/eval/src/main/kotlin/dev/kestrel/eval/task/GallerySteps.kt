package dev.kestrel.eval.task

import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.device.TestPhotos
import dev.kestrel.eval.hook.HookAnswer
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

/** The galleries whose viewer photo-latest accepts: Samsung Gallery, the A71's own, and Google Photos, kestrel-api37's. */
object Gallery {
    const val SAMSUNG = "com.sec.android.gallery3d"
    const val PHOTOS = "com.google.android.apps.photos"
    val PACKAGES = listOf(SAMSUNG, PHOTOS)

    /** The key of [RunScope.notes] that holds the test photo's time, as `h:mm` and `H:mm`. */
    const val TIMES = "photo-times"

    /**
     * The galleries' activities that show one photo and never the grid: those that a VIEW intent opens. Candidates that
     * Task 10 of plan 6B confirms on kestrel-api37 and Task 12 on the A71.
     */
    val VIEWERS = setOf(
        "$PHOTOS/com.google.android.apps.photos.pager.HostPhotoPagerActivity",
        "$SAMSUNG/com.samsung.android.gallery.app.activity.external.GalleryExternalActivity",
    )

    /** Whether [activity], as `dumpsys activity` gives it ("package/.Class" or "package/full.Class"), is one of [VIEWERS]. */
    fun viewer(activity: String): Boolean {
        val packageName = activity.substringBefore('/')
        val name = activity.substringAfter('/')
        return "$packageName/${if (name.startsWith(".")) packageName + name else name}" in VIEWERS
    }
}

/**
 * photo-latest's photo (design D8 of sub-project 6): one card taken a minute ago on the device's clock, the newest image
 * ([TestPhotos.addNewest]); its time as a viewer may show it, on a 12-hour and on a 24-hour clock, goes into
 * [RunScope.notes]. Its restore, pushed first, deletes the folder and checks the count of images.
 */
data object NewestPhoto : Setup {
    private val H12 = DateTimeFormatter.ofPattern("h:mm")
    private val H24 = DateTimeFormatter.ofPattern("H:mm")

    override fun apply(scope: RunScope): SetupResult {
        val photos = TestPhotos(scope.device, scope.work.resolve("photos"))
        val before = photos.imageCount()
        val byHand = "Delete ${TestPhotos.FOLDER} on the device."
        scope.restores.push("the test photo", byHand) {
            check(photos.remove(before)) { "The device does not have its $before images of before the test photo again." }
        }
        val taken = try {
            photos.addNewest { copy -> scope.restores.change("the test photo", byHand, copy) }
        } catch (e: IllegalStateException) {
            throw SetupFailed(e.message.orEmpty())
        }
        scope.notes[Gallery.TIMES] = listOf(taken.format(H12), taken.format(H24)).distinct().joinToString(",")
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf("images" to "${TestPhotos(scope.device, scope.work.resolve("photos")).imageCount()}")
}

/** A rehearsal's viewer: the test photo opened in the device's gallery by its media `_id`; the gallery is stopped afterwards. */
data object ViewNewestPhoto : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val gallery = Gallery.PACKAGES.firstOrNull { Installed.enabled(scope, it) } ?: return SetupResult.Untestable("no gallery is installed")
        val id = Dumps.largestRowId(scope.device.read("content query --uri ${TestPhotos.IMAGES} --projection _id --where ${TestPhotos.WHERE}"))
        if (id == 0L) throw SetupFailed("The test photo is not in the media store.")
        scope.restores.push("$gallery stopped", "Close the gallery.") { scope.device.idempotent("am force-stop $gallery") }
        scope.restores.change("the test photo opened", "Close the gallery.") {
            // A lost answer is read back by the task's check.
            scope.device.once("am start -W -a android.intent.action.VIEW -d ${TestPhotos.IMAGES}/$id -t image/jpeg -p $gallery")
        }
        scope.clock.sleep(5.seconds)
        return SetupResult.Ready
    }
}

/**
 * The newest photo shows in a gallery's viewer (design D8 of sub-project 6): a gallery of [Gallery.PACKAGES] is in front,
 * and Kestrel's tree of the screen holds exactly one image that covers most of the gallery's window, the test card,
 * because the setup made it the newest item; or the gallery's viewer activity ([Gallery.VIEWERS]) is in front and its
 * screen holds the card's time. The time alone does not count, because a gallery's grid describes its thumbnails with
 * their times too, such as Google Photos' "Photo taken on Nov 23, 2026 1:04:00 PM".
 */
data object PhotoViewer : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val activity = Dumps.resumedActivity(scope.device.read("dumpsys activity activities")) ?: return false
        val front = activity.substringBefore('/')
        if (front !in Gallery.PACKAGES) return false
        if (Gallery.viewer(activity)) {
            val times = scope.notes[Gallery.TIMES]?.split(',').orEmpty()
            if (times.any { scope.hooks.screenHas(packageName = front, contains = it).int("count") >= 1 }) return true
        }
        val path = scope.hooks.agentTree()["path"] ?: return false
        return Dumps.largeImageCount(scope.device.read("run-as dev.kestrel cat $path").joinToString("\n")) == 1
    }
}
