package dev.kestrel.app.download

import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.models.DownloadFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRulesTest {
    @Test
    fun withoutMobileDataOnlyANetworkThatDoesNotCountItsDataMayDownload() {
        assertEquals(DownloadFailure.NoNetwork, downloadBlockedBy(NetworkKind.NONE, mobileData = true))
        assertEquals(DownloadFailure.MeteredNetwork(), downloadBlockedBy(NetworkKind.METERED, mobileData = false))
        assertNull(downloadBlockedBy(NetworkKind.METERED, mobileData = true))
        // A Wi-Fi that Android rates as metered, such as a phone's hotspot, gets a reason of its own.
        assertEquals(DownloadFailure.MeteredNetwork(wifi = true), downloadBlockedBy(NetworkKind.METERED_WIFI, mobileData = false))
        assertNull(downloadBlockedBy(NetworkKind.METERED_WIFI, mobileData = true))
        assertNull(downloadBlockedBy(NetworkKind.UNMETERED, mobileData = false))
    }

    @Test
    fun kokoroIsChosenFirstOnlyOnAFastArmPhoneWithEnoughMemory() {
        // The S23+: a Snapdragon 8 Gen 2, whose fastest core runs at 3.36 GHz, and 8 GB of memory.
        val s23 = Hardware(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), totalMemoryBytes = 7_400_000_000L, fastestCoreKhz = 3_360_000L)

        assertEquals(VoiceChoice.KOKORO, VoiceSuggestion.of(s23))
        // The A71's fastest cores run at 2.2 GHz, where Kokoro needed 1.81 s for each second of speech.
        assertEquals(VoiceChoice.PIPER, VoiceSuggestion.of(s23.copy(fastestCoreKhz = 2_208_000L)))
        assertEquals(VoiceChoice.PIPER, VoiceSuggestion.of(s23.copy(totalMemoryBytes = 3_700_000_000L)))
        // kestrel-api37 is an x86_64 emulator.
        assertEquals(VoiceChoice.PIPER, VoiceSuggestion.of(s23.copy(abis = listOf("x86_64"))))
        // A phone whose CPU speed Kestrel may not read.
        assertEquals(VoiceChoice.PIPER, VoiceSuggestion.of(s23.copy(fastestCoreKhz = null)))
    }
}
