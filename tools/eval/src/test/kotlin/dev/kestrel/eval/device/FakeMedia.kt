package dev.kestrel.eval.device

/**
 * A device's media store for the tests of the test photos: the owner's rows and the test folder's, which a scan adds and
 * `rm -rf` deletes. The device's date is 2026-11-23 in +01:00, so "yesterday" is 2026-11-22.
 */
class FakeMedia(adb: FakeAdb) {
    val owner = mutableListOf("Row: 0 _id=12, datetaken=1700000000000, date_added=1700000000")
    val test = mutableListOf<String>()

    /** The DATE_TAKEN that a scan gives the newest card: 13:04 of 2026-11-23 in +01:00, a minute before 13:05:30. */
    var newestTaken = 1795435440000L

    init {
        adb.on("^date \\+%z$") { "+0100" }
        adb.on("^date \\+%Y-%m-%d$") { "2026-11-23" }
        adb.on("^content query --uri content://media/external/images/media --projection _id:datetaken:date_added$") {
            (owner + test).joinToString("\n").ifEmpty { "No result found." }
        }
        adb.on("^content query .* --where ") { test.joinToString("\n").ifEmpty { "No result found." } }
        // Noon of 2026-11-22 in +01:00 is 1795345200000 ms.
        adb.on("^content call --uri content://media/ --method scan_file --arg (\\S+)$") {
            val taken = if (it.groupValues[1].endsWith("kestrel_test_newest.jpg")) newestTaken else 1795345200000L
            test.add("Row: ${test.size} _id=${100 + test.size}, datetaken=$taken, date_added=1795345300")
            ""
        }
        adb.on("^rm -rf ") { test.clear(); "" }
    }
}
