package dev.kestrel.build.audit

/** One resolved external dependency. */
data class Coordinates(val group: String, val name: String, val version: String) {
    override fun toString(): String = "$group:$name:$version"
}

/** A `group:name` pattern in which `*` matches any run of characters. */
class CoordinatePattern(val text: String) {
    private val group: Regex
    private val name: Regex

    init {
        val parts = text.split(':')
        require(parts.size == 2 && parts.all { it.isNotEmpty() }) { "Expected group:name but was '$text'" }
        group = globToRegex(parts[0])
        name = globToRegex(parts[1])
    }

    fun matches(coordinates: Coordinates): Boolean =
        group.matches(coordinates.group) && name.matches(coordinates.name)
}

internal fun globToRegex(glob: String): Regex =
    Regex(glob.split('*').joinToString(".*") { Regex.escape(it) })

data class AllowRule(val pattern: CoordinatePattern, val license: String)
data class DenyRule(val pattern: CoordinatePattern, val reason: String)
data class PinRule(val pattern: CoordinatePattern, val versionGlob: String)
/** Exempts the classes under [classPrefix] in the module [modulePath], such as `:core:privacy`, and in no other module. */
data class NetworkClassException(val modulePath: String, val classPrefix: String, val justification: String)
data class NativeSymbolException(val libraryName: String, val symbol: String, val justification: String)

/** The parsed contents of `audit/rules.txt`. */
class AuditRules(
    val allows: List<AllowRule>,
    val denies: List<DenyRule>,
    val pins: List<PinRule>,
    val networkClassExceptions: List<NetworkClassException>,
    val nativeSymbolExceptions: List<NativeSymbolException>,
    val modelHosts: List<ModelHostRule> = emptyList(),
    val modelDownloadTagExceptions: List<NetworkClassException> = emptyList(),
) {
    /** Returns one message per problem. An empty list means that every dependency passed. */
    fun checkDependencies(dependencies: Collection<Coordinates>): List<String> {
        val problems = mutableListOf<String>()
        for (dependency in dependencies.sortedBy { it.toString() }) {
            val deny = denies.firstOrNull { it.pattern.matches(dependency) }
            if (deny != null) {
                problems += "$dependency is denied: ${deny.reason}"
                continue
            }
            val pin = pins.firstOrNull { it.pattern.matches(dependency) }
            if (pin != null && !globToRegex(pin.versionGlob).matches(dependency.version)) {
                problems += "$dependency must have a version that matches ${pin.versionGlob}"
            }
            val allow = allows.firstOrNull { it.pattern.matches(dependency) }
            when {
                allow == null ->
                    problems += "$dependency has no allow rule; review its licence and add a rule to audit/rules.txt"
                allow.license !in GPL3_COMPATIBLE_LICENSES ->
                    problems += "$dependency uses ${allow.license}, which is not compatible with GPL-3.0"
            }
        }
        return problems
    }

    fun isNetworkClassExcepted(modulePath: String, internalClassName: String): Boolean =
        networkClassExceptions.any { it.modulePath == modulePath && internalClassName.startsWith(it.classPrefix) }

    /** Whether the class may use the model download's tag, which lets a request reach the model hosts (release plan). */
    fun isModelDownloadTagExcepted(modulePath: String, internalClassName: String): Boolean =
        modelDownloadTagExceptions.any { it.modulePath == modulePath && internalClassName.startsWith(it.classPrefix) }

    fun isNativeSymbolExcepted(libraryName: String, symbol: String): Boolean =
        nativeSymbolExceptions.any { it.libraryName == libraryName && it.symbol == symbol }

    /**
     * Returns one message per problem with the model hosts of [manifest], the text of `models/manifest.json` (release
     * plan): each of its `downloadHosts` needs an `allow-model-host` rule, and each address of a file or an archive needs
     * a host that its `downloadHosts` cover. An empty list means that every host passed.
     */
    fun checkModelHosts(manifest: String): List<String> {
        val listed = ManifestHosts.downloadHosts(manifest)
        val problems = mutableListOf<String>()
        for (pattern in listed) {
            if (modelHosts.none { it.pattern == pattern }) {
                problems += "The download host $pattern has no allow-model-host rule in audit/rules.txt"
            }
        }
        for (host in ManifestHosts.urlHosts(manifest)) {
            if (listed.none { hostMatches(it, host) }) {
                problems += "The models' manifest downloads from $host, which its downloadHosts do not cover"
            }
        }
        return problems
    }

    companion object {
        val GPL3_COMPATIBLE_LICENSES = setOf(
            "Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Zlib", "CC0-1.0",
            "Unlicense", "MPL-2.0", "LGPL-2.1-or-later", "LGPL-3.0-only", "LGPL-3.0-or-later",
            "GPL-3.0-only", "GPL-3.0-or-later",
        )

        fun parse(text: String): AuditRules {
            val allows = mutableListOf<AllowRule>()
            val denies = mutableListOf<DenyRule>()
            val pins = mutableListOf<PinRule>()
            val networkClasses = mutableListOf<NetworkClassException>()
            val nativeSymbols = mutableListOf<NativeSymbolException>()
            val modelHostRules = mutableListOf<ModelHostRule>()
            val modelDownloadTags = mutableListOf<NetworkClassException>()
            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val parts = line.split(Regex("\\s+"))
                fun rest(from: Int) = parts.drop(from).joinToString(" ")
                fun fail(message: String): Nothing =
                    throw IllegalArgumentException("audit/rules.txt line ${index + 1}: $message")
                when (parts[0]) {
                    "allow" -> {
                        if (parts.size != 3) fail("expected 'allow <group:name> <SPDX licence>'")
                        allows += AllowRule(CoordinatePattern(parts[1]), parts[2])
                    }
                    "deny" -> {
                        if (parts.size < 3) fail("expected 'deny <group:name> <reason>'")
                        denies += DenyRule(CoordinatePattern(parts[1]), rest(2))
                    }
                    "pin" -> {
                        if (parts.size != 3) fail("expected 'pin <group:name> <version glob>'")
                        pins += PinRule(CoordinatePattern(parts[1]), parts[2])
                    }
                    "allow-network-class" -> {
                        if (parts.size < 4 || !parts[1].startsWith(":")) {
                            fail("expected 'allow-network-class <module path> <class prefix> <justification>'")
                        }
                        networkClasses += NetworkClassException(parts[1], parts[2], rest(3))
                    }
                    "allow-native-symbol" -> {
                        if (parts.size < 4) fail("expected 'allow-native-symbol <library.so> <symbol> <justification>'")
                        nativeSymbols += NativeSymbolException(parts[1], parts[2], rest(3))
                    }
                    "allow-model-host" -> {
                        if (parts.size < 3 || !MODEL_HOST_PATTERN.matches(parts[1])) {
                            fail("expected 'allow-model-host <host or *.domain> <justification>'")
                        }
                        modelHostRules += ModelHostRule(parts[1], rest(2))
                    }
                    "allow-model-download-tag" -> {
                        if (parts.size < 4 || !parts[1].startsWith(":")) {
                            fail("expected 'allow-model-download-tag <module path> <class prefix> <justification>'")
                        }
                        modelDownloadTags += NetworkClassException(parts[1], parts[2], rest(3))
                    }
                    else -> fail("unknown rule '${parts[0]}'")
                }
            }
            return AuditRules(allows, denies, pins, networkClasses, nativeSymbols, modelHostRules, modelDownloadTags)
        }
    }
}
