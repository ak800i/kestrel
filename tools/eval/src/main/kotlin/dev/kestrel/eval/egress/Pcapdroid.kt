package dev.kestrel.eval.egress

/** A release file, pinned by its size and SHA-256. */
data class Release(val url: String, val file: String, val size: Long, val sha256: String)

/** PCAPdroid 2.0.4 (design D11 of sub-project 6): its package, its control activity, its pinned release and its DNS servers. */
object Pcapdroid {
    const val PACKAGE = "com.emanuelef.remote_capture"
    const val CONTROL = "$PACKAGE/.activities.CaptureCtrl"
    val RELEASE = Release(
        "https://github.com/emanuele-f/PCAPdroid/releases/download/v2.0.4/PCAPdroid_v2.0.4.apk",
        "PCAPdroid_v2.0.4.apk",
        17_641_581L,
        "b601798767bb8c989a2ec389e4d21a2a0ab2ae2c2f9292238b4e3ee2f52af811",
    )

    /** PCAPdroid's virtual IPv4 DNS server inside its VPN (`CaptureService.VPN_VIRTUAL_DNS_SERVER`), which it answers itself. */
    const val DNS_V4 = "10.215.173.2"

    /**
     * The IPv6 DNS server that PCAPdroid gives its VPN, its `dns_v6` setting's default (`Prefs.getDnsServerV6`), as
     * [PcapReader] writes addresses: hexadecimal groups without leading zeros and without `::`. A device whose setting
     * names another server fails the check with port-53 packets of another UID.
     */
    const val DNS_V6 = "2620:fe:0:0:0:0:0:10"

    /** Whether [address] is one of PCAPdroid's DNS servers, whose packets on port 53 are exempt from the UID rule (design D11). */
    fun dnsServer(address: String): Boolean = address == DNS_V4 || address == DNS_V6
}
