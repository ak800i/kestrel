package dev.kestrel.app.download

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.models.DownloadFailure
import java.io.File
import java.io.IOException

/** The network that the phone uses now, for the model download (release plan). */
enum class NetworkKind { NONE, UNMETERED, METERED }

/**
 * Why a download of the models may not use a network of [kind] now, or null when it may: it needs a network, and one that
 * counts its data, such as mobile data, only with [mobileData] (release plan).
 */
fun downloadBlockedBy(kind: NetworkKind, mobileData: Boolean): DownloadFailure? = when {
    kind == NetworkKind.NONE -> DownloadFailure.NoNetwork
    kind == NetworkKind.METERED && !mobileData -> DownloadFailure.MeteredNetwork
    else -> null
}

/** The phone's network now, as Android rates it: Wi-Fi usually does not count its data, and mobile data does. */
fun networkKind(context: Context): NetworkKind {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return NetworkKind.NONE
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkKind.NONE
    return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) NetworkKind.UNMETERED else NetworkKind.METERED
}

/** What the voice's suggestion knows of the phone: its ABIs, its memory as Android reports it, and its fastest CPU core. */
data class Hardware(val abis: List<String>, val totalMemoryBytes: Long, val fastestCoreKhz: Long?)

/** Which voice step 2 of the setup checklist chooses first (design spec, section 8.2; release plan). */
object VoiceSuggestion {
    /** A phone with "6 GB" of memory reports about 5.5 to 5.8 GB. */
    const val MIN_MEMORY_BYTES = 5_500_000_000L

    /** 2.8 GHz: the A71's fastest cores run at 2.2 GHz, and the S23+'s fastest core at 3.36 GHz. */
    const val MIN_CORE_KHZ = 2_800_000L

    /**
     * Kokoro on an arm64 phone with at least [MIN_MEMORY_BYTES] of memory and a core of at least [MIN_CORE_KHZ], else
     * Piper. On the A71, Kokoro's fp32 model needed 1.81 s for each second of speech, and Kestrel's memory peaked at
     * 1037 MB with Kokoro. Kestrel still measures Kokoro when it first speaks with it, and Piper speaks where Kokoro is too
     * slow.
     */
    fun of(hardware: Hardware): VoiceChoice = when {
        ARM64 !in hardware.abis -> VoiceChoice.PIPER
        hardware.totalMemoryBytes < MIN_MEMORY_BYTES -> VoiceChoice.PIPER
        (hardware.fastestCoreKhz ?: 0L) < MIN_CORE_KHZ -> VoiceChoice.PIPER
        else -> VoiceChoice.KOKORO
    }

    private const val ARM64 = "arm64-v8a"
}

/**
 * Reads the phone's [Hardware]. The fastest core comes from the kernel's files in /sys; where Android does not let Kestrel
 * read them, it is null, and Piper comes first. It reads files, so call it off the main thread.
 */
fun readHardware(context: Context): Hardware {
    val memory = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
    return Hardware(Build.SUPPORTED_ABIS.toList(), memory.totalMem, fastestCoreKhz())
}

private val CPU = Regex("cpu\\d+")

private fun fastestCoreKhz(): Long? = try {
    File("/sys/devices/system/cpu").listFiles { file -> CPU.matches(file.name) }.orEmpty()
        .mapNotNull { File(it, "cpufreq/cpuinfo_max_freq").takeIf(File::canRead)?.readText()?.trim()?.toLongOrNull() }
        .maxOrNull()
} catch (e: IOException) {
    null
} catch (e: SecurityException) {
    null
}
