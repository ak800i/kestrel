package dev.kestrel.eval.task

import dev.kestrel.eval.device.TestPhotos

/** [count] test photos of [day] in the device's gallery (design D4 of sub-project 6), deleted after the run with a check of the count. */
data class Photos(val day: String, val count: Int) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val photos = TestPhotos(scope.device, scope.work.resolve("photos"))
        val before = photos.imageCount()
        scope.restores.push("the test photos", "Delete ${TestPhotos.FOLDER} on the device.") {
            check(photos.remove(before)) { "The device does not have its $before images of before the test photos again." }
        }
        try {
            photos.add(day, count)
        } catch (e: IllegalStateException) {
            throw SetupFailed(e.message.orEmpty())
        }
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf("images" to "${TestPhotos(scope.device, scope.work.resolve("photos")).imageCount()}")
}
