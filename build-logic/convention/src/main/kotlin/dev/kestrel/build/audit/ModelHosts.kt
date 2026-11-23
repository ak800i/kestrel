package dev.kestrel.build.audit

/**
 * A host from which the app may download its model files, or every host below a domain, written `*.domain`, with the
 * written justification of its `allow-model-host` rule in `audit/rules.txt` (release plan).
 */
data class ModelHostRule(val pattern: String, val justification: String)

/** The form of a model host: a host name, or `*.` and a domain. */
internal val MODEL_HOST_PATTERN = Regex("(\\*\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)+")

/** Whether [host] is [pattern], or lies below the domain of a pattern `*.domain`, which the domain itself does not. */
fun hostMatches(pattern: String, host: String): Boolean =
    if (pattern.startsWith("*.")) host.length > pattern.length - 1 && host.endsWith(pattern.substring(1)) else host == pattern

/** Reads the hosts of `models/manifest.json` with regular expressions, because build-logic has no JSON library. */
internal object ManifestHosts {
    private val url = Regex("\"url\"\\s*:\\s*\"https://([^/\":]+)[/:]")
    private val list = Regex("\"downloadHosts\"\\s*:\\s*\\[([^\\]]*)]")
    private val item = Regex("\"([^\"]*)\"")

    /** The host of every HTTPS address of the manifest's files and archives. */
    fun urlHosts(manifest: String): Set<String> = url.findAll(manifest).map { it.groupValues[1].lowercase() }.toSortedSet()

    /** The manifest's `downloadHosts`, in their order. */
    fun downloadHosts(manifest: String): List<String> =
        list.find(manifest)?.groupValues?.get(1)?.let { inner -> item.findAll(inner).map { it.groupValues[1] }.toList() }.orEmpty()
}
