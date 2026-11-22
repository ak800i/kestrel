package dev.kestrel.datatools.media

import android.Manifest
import dev.kestrel.agent.port.MediaKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MediaQueryTest {
    private val belgrade = ZoneId.of("Europe/Belgrade")

    private fun millis(text: String) = LocalDateTime.parse(text).atZone(belgrade).toInstant().toEpochMilli()

    @Test
    fun theBoundsAreTheLocalDaysInTheDevicesZone() {
        val day = LocalDate.of(2026, 11, 19)

        assertEquals(millis("2026-11-19T00:00") to millis("2026-11-20T00:00"), MediaQuery.bounds(day, day, belgrade))
        // The day on which the clocks go forward has 23 hours, and a range ends at the start of the day after it.
        val spring = LocalDate.of(2026, 3, 29)
        val (start, end) = MediaQuery.bounds(spring, spring, belgrade)
        assertEquals(23L * 3_600_000, end - start)
        assertEquals(millis("2026-11-21T00:00"), MediaQuery.bounds(day, day.plusDays(1), belgrade).second)
    }

    @Test
    fun anItemWithoutADateTakenCountsByWhenItWasAdded() {
        assertEquals(1_795_086_000_000L, MediaQuery.taken(1_795_086_000_000L, 1_795_100_000L))
        assertEquals(1_795_100_000_000L, MediaQuery.taken(null, 1_795_100_000L))
        assertEquals(1_795_100_000_000L, MediaQuery.taken(0L, 1_795_100_000L))
    }

    @Test
    fun aLineNamesTheItemItsKindTimeAndSizeAndNothingOfTheFile() {
        val line = MediaQuery.line("m2", video = false, takenMillis = millis("2026-11-19T12:05"), sizeBytes = 2_160_000, zone = belgrade)

        assertEquals("m2 · photo · 2026-11-19 12:05 · 2.2 MB", line)
        assertEquals("m3 · video · 2026-11-19 12:00 · 640 KB", MediaQuery.line("m3", true, millis("2026-11-19T12:00"), 640_000, belgrade))
        assertEquals("900 B", MediaQuery.size(900))
        assertFalse("jpg" in line || "/" in line)
    }

    @Test
    fun theShareTypeIsImagesForPhotosVideoForOneVideoAndAnythingOtherwise() {
        assertEquals("image/*", ShareIntent.mimeType(photos = 3, videos = 0))
        assertEquals("video/*", ShareIntent.mimeType(photos = 0, videos = 1))
        assertEquals("*/*", ShareIntent.mimeType(photos = 0, videos = 2))
        assertEquals("*/*", ShareIntent.mimeType(photos = 2, videos = 1))
    }

    @Test
    fun accessIsFullOnlySelectedOrNoneAndThePermissionsAreAskedTogether() {
        val images = Manifest.permission.READ_MEDIA_IMAGES
        val video = Manifest.permission.READ_MEDIA_VIDEO
        val selected = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED

        assertEquals(listOf(images, video), MediaQuery.permissions(MediaKinds.PHOTOS, sdk = 33))
        assertEquals(listOf(images, video, selected), MediaQuery.permissions(MediaKinds.BOTH, sdk = 34))
        assertEquals(MediaQuery.Access.FULL, MediaQuery.access(MediaKinds.PHOTOS, 34) { it == images })
        assertEquals(MediaQuery.Access.SELECTED, MediaQuery.access(MediaKinds.BOTH, 34) { it == images || it == selected })
        assertEquals(MediaQuery.Access.NONE, MediaQuery.access(MediaKinds.VIDEOS, 33) { it == images })
        // Android 13 has no partial access, so the permission of Android 14 never counts there.
        assertEquals(MediaQuery.Access.NONE, MediaQuery.access(MediaKinds.PHOTOS, 33) { it == selected })
    }

    @Test
    fun aBusyDayListsItsNewestItemsInTimeOrderAndTellsTheModelHowManyThereAre() {
        val found = (1..42).toList()

        val result = MediaQuery.result(found, MediaQuery.Access.FULL) { "m$it" }

        assertEquals((13..42).map { "m$it" } + "Kestrel lists the newest 30 of 42 items.", result.lines.map { it.fixed })
        assertEquals(emptyList<Any>(), result.lines.last().fields)
        assertNull(result.note)
        assertEquals((1..30).map { "m$it" }, MediaQuery.result((1..30).toList(), MediaQuery.Access.FULL) { "m$it" }.lines.map { it.fixed })
    }

    @Test
    fun theNoteThatTheOwnerHearsIsOnlyThePartialAccessSentence() {
        val result = MediaQuery.result((1..42).toList(), MediaQuery.Access.SELECTED) { "m$it" }

        assertEquals(MediaQuery.PARTIAL, result.note)
        assertEquals(31, result.lines.size)
    }
}
