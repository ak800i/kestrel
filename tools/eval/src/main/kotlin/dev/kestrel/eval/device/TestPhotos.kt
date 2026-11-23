package dev.kestrel.eval.device

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Test photos in `Pictures/KestrelTest` on a test device (design D5 of sub-project 6; the port of scripts\lib\Photos.ps1):
 * [add] puts cards dated at noon of a day there and has Android index them, and [remove] deletes the folder again. They
 * read only counts of the device's images, never a file name of the owner's or an image.
 */
class TestPhotos(private val device: Device, private val work: Path) {
    /** The number of images that the device's media store holds. */
    fun imageCount(): Int = rows("").size

    /**
     * Puts [count] cards for noon of [day] ("today" or "yesterday", on the device's clock) into the folder and indexes
     * them. It refuses when the device already has an image of that day, which a task would share too, and it removes
     * what it copied when Android does not date every card on that day. Each change on the device (the folder, and each
     * card's copy, date and index) runs inside [change], through which the caller can refuse it once its restore runs.
     */
    fun add(day: String, count: Int, change: (() -> Unit) -> Unit = { it() }) {
        require(count in 1..10) { "A test may add 1 to 10 photos." }
        val offset = PhotoCard.exifOffset(device.read("date +%z").joinToString("").trim())
        val today = LocalDate.parse(device.read("date +%Y-%m-%d").joinToString("").trim())
        val date = when (day) {
            "today" -> today
            "yesterday" -> today.minusDays(1)
            else -> throw IllegalArgumentException("The day must be today or yesterday, not '$day'.")
        }
        val start = date.atStartOfDay().toInstant(ZoneOffset.of(offset)).toEpochMilli()
        val end = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.of(offset)).toEpochMilli()
        if (rows("").any { row -> row.time()?.let { it in start until end } == true }) {
            throw IllegalStateException("The device already has photos of $date; the test would share them too.")
        }
        val before = imageCount()
        val taken = date.atTime(12, 0)
        try {
            change { device.idempotent("mkdir -p $FOLDER") }
            Files.createDirectories(work)
            for (number in 1..count) {
                val local = work.resolve("kestrel_test_$number.jpg")
                Files.write(local, PhotoCard.jpeg(number, taken, offset))
                val remote = "$FOLDER/kestrel_test_$number.jpg"
                change {
                    device.push(local, remote)
                    device.idempotent("touch -m -d '${taken.format(TOUCH)}' $remote")
                    device.idempotent("content call --uri content://media/ --method scan_file --arg $remote")
                }
            }
            val test = rows(" --where $WHERE")
            val dated = test.count { it.taken != null && it.taken in start until end }
            if (test.size != count || dated != count) {
                throw IllegalStateException("Android indexed ${test.size} of the $count test photos and dated $dated of them on $date.")
            }
        } catch (e: Exception) {
            // A failed removal must not hide why the copy stopped, such as the caller's refusal after Ctrl+C.
            try {
                remove(before)
            } catch (cleanup: Exception) {
                e.addSuppressed(cleanup)
            }
            throw e
        }
    }

    /** Deletes the folder, which deletes its rows, deletes any row that is left, and returns whether the device has [before] images again. */
    fun remove(before: Int): Boolean {
        device.idempotent("rm -rf $FOLDER")
        if (rows(" --where $WHERE").isNotEmpty()) device.idempotent("content delete --uri $IMAGES --where $WHERE")
        return rows(" --where $WHERE").isEmpty() && imageCount() == before
    }

    private fun rows(filter: String): List<Row> =
        device.read("content query --uri $IMAGES --projection _id:datetaken:date_added$filter").filter { ROW.containsMatchIn(it) }.map { line ->
            fun value(name: String) = Regex("\\b$name=(-?\\d+)").find(line)?.groupValues?.get(1)?.toLong()
            Row(value("datetaken"), value("date_added"))
        }

    /** A row's date: DATE_TAKEN in milliseconds, or DATE_ADDED in seconds when it has none, as find_media counts them. */
    private data class Row(val taken: Long?, val added: Long?) {
        fun time(): Long? = taken?.takeIf { it != 0L } ?: added?.let { it * 1000 }
    }

    companion object {
        const val FOLDER = "/storage/emulated/0/Pictures/KestrelTest"
        const val IMAGES = "content://media/external/images/media"

        /** The where clause of the folder's rows, quoted for the device's shell. */
        const val WHERE = "'_data LIKE '\\''$FOLDER/%'\\'''"
        private val TOUCH = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val ROW = Regex("^Row: \\d+ ")
    }
}
