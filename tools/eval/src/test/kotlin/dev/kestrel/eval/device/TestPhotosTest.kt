package dev.kestrel.eval.device

import dev.kestrel.eval.task.FakeRun
import dev.kestrel.eval.task.Photos
import dev.kestrel.eval.task.SetupFailed
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** The test photos: the EXIF segment of Photos.ps1, the card, and their copy, index and removal on a device (design D5 of sub-project 6). */
class TestPhotosTest {
    private val noon = LocalDateTime.of(2026, 11, 22, 12, 0, 0)

    @Test
    fun theExifSegmentIsPhotosPs1sByteForByte() {
        val segment = PhotoCard.exifSegment(noon, "+01:00")

        // FF E1, the length 92, "Exif" and two zero bytes, then the TIFF of 84 bytes: 2 + 2 + 90 = 94.
        assertEquals(94, segment.size)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 92), segment.copyOfRange(0, 4))
        assertEquals("Exif", String(segment, 4, 4, Charsets.US_ASCII))
        val tiff = 10
        assertEquals("II", String(segment, tiff, 2, Charsets.US_ASCII))
        assertEquals("2026:11:22 12:00:00\u0000", String(segment, tiff + 56, 20, Charsets.US_ASCII))
        assertEquals("+01:00\u0000", String(segment, tiff + 76, 7, Charsets.US_ASCII))
        assertEquals("+01:00", PhotoCard.exifOffset("+0100"))
        assertEquals("-05:30", PhotoCard.exifOffset("-0530"))
    }

    @Test
    fun theCardIsAJpegWithTheSegmentRightAfterItsStart() {
        val jpeg = PhotoCard.jpeg(2, noon, "+01:00")

        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()), jpeg.copyOfRange(0, 4))
        assertArrayEquals(PhotoCard.exifSegment(noon, "+01:00"), jpeg.copyOfRange(2, 96))
    }

    @Test
    fun photosAreCopiedIndexedAndRemovedWithACheckOfTheCount() {
        val run = FakeRun()
        val media = FakeMedia(run.adb)

        Photos("yesterday", 3).apply(run.scope)

        assertEquals(3, media.test.size)
        assertEquals(3, run.adb.hostCommands.count { it.first() == "push" })
        assertTrue(run.adb.commands.contains("touch -m -d '2026-11-22 12:00:00' ${TestPhotos.FOLDER}/kestrel_test_1.jpg"))
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(0, media.test.size)
        assertTrue(run.adb.commands.contains("rm -rf ${TestPhotos.FOLDER}"))
    }

    @Test
    fun aDayThatAlreadyHasPhotosIsRefusedBeforeAnythingIsCopied() {
        val run = FakeRun()
        val media = FakeMedia(run.adb)
        // An image of the owner's, taken on 2026-11-22 at 10:00 in +01:00.
        media.owner.add("Row: 0 _id=31, datetaken=1795338000000, date_added=1795340000")

        assertThrows(SetupFailed::class.java) { Photos("yesterday", 3).apply(run.scope) }
        assertFalse(run.adb.hostCommands.any { it.first() == "push" })
        assertTrue(run.restores.runAll().isEmpty())
    }

}
