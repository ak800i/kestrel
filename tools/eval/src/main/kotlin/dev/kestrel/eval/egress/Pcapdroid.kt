package dev.kestrel.eval.egress

/** A release file, pinned by its size and SHA-256. */
data class Release(val url: String, val file: String, val size: Long, val sha256: String)

/** PCAPdroid 2.0.4 (design D11 of sub-project 6): its package, its control activity, its pinned release and its own DNS server. */
object Pcapdroid {
    const val PACKAGE = "com.emanuelef.remote_capture"
    const val CONTROL = "$PACKAGE/.activities.CaptureCtrl"
    val RELEASE = Release(
        "https://github.com/emanuele-f/PCAPdroid/releases/download/v2.0.4/PCAPdroid_v2.0.4.apk",
        "PCAPdroid_v2.0.4.apk",
        17_641_581L,
        "b601798767bb8c989a2ec389e4d21a2a0ab2ae2c2f9292238b4e3ee2f52af811",
    )

    /** The DNS server inside PCAPdroid's VPN, whose packets on port 53 are exempt from the UID rule (design D11). */
    const val DNS_V4 = "10.215.173.2"

    /** The start of PCAPdroid's IPv6 VPN network, as [PcapReader] writes addresses, in which its IPv6 DNS server lies. */
    const val VPN_V6_PREFIX = "fd00:"

    /** Whether [address] is PCAPdroid's own DNS server; Task 15 of plan 6B confirms the IPv6 side in its first capture. */
    fun dnsServer(address: String): Boolean = address == DNS_V4 || address.startsWith(VPN_V6_PREFIX)
}
