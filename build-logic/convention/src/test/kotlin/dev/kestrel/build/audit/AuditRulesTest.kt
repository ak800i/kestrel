package dev.kestrel.build.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuditRulesTest {
    private val rules = AuditRules.parse(
        """
        # A comment, followed by a blank line.

        deny com.google.firebase:* Firebase sends analytics to Google
        pin com.microsoft.onnxruntime:* 1.28.*
        allow androidx.*:* Apache-2.0
        allow com.microsoft.onnxruntime:* MIT
        allow org.example:gpl2 GPL-2.0-only
        allow-network-class dev/kestrel/tools/ Runs on the PC only
        allow-native-symbol libexample.so connect Connects to a local socket only
        """.trimIndent(),
    )

    @Test
    fun allowedDependencyPasses() {
        assertEquals(emptyList<String>(), rules.checkDependencies(listOf(Coordinates("androidx.core", "core-ktx", "1.19.1"))))
    }

    @Test
    fun deniedDependencyFailsEvenWithoutAnAllowRule() {
        val problems = rules.checkDependencies(listOf(Coordinates("com.google.firebase", "firebase-analytics", "23.0.0")))
        assertEquals(listOf("com.google.firebase:firebase-analytics:23.0.0 is denied: Firebase sends analytics to Google"), problems)
    }

    @Test
    fun projectRulesDenyAndroidxAdvertisingLibrariesThatTheAndroidxAllowRuleMatches() {
        // Gradle runs the tests in build-logic/convention.
        val projectRules = AuditRules.parse(File("../../audit/rules.txt").readText())
        val advertising = listOf(
            Coordinates("androidx.ads", "ads-identifier", "1.0.0-alpha05"),
            Coordinates("androidx.privacysandbox.ads", "ads-adservices", "1.1.0"),
        )

        assertTrue(advertising.all { dependency -> projectRules.allows.any { it.pattern.matches(dependency) } })
        assertEquals(
            listOf(
                "androidx.ads:ads-identifier:1.0.0-alpha05 is denied: advertising ID",
                "androidx.privacysandbox.ads:ads-adservices:1.1.0 is denied: Privacy Sandbox advertising APIs",
            ),
            projectRules.checkDependencies(advertising),
        )
    }

    @Test
    fun dependencyWithoutAllowRuleFails() {
        val problems = rules.checkDependencies(listOf(Coordinates("org.unknown", "lib", "1.0")))
        assertEquals(
            listOf("org.unknown:lib:1.0 has no allow rule; review its licence and add a rule to audit/rules.txt"),
            problems,
        )
    }

    @Test
    fun incompatibleLicenceFails() {
        val problems = rules.checkDependencies(listOf(Coordinates("org.example", "gpl2", "1.0")))
        assertEquals(listOf("org.example:gpl2:1.0 uses GPL-2.0-only, which is not compatible with GPL-3.0"), problems)
    }

    @Test
    fun pinnedVersionPassesAndOtherVersionFails() {
        assertEquals(
            emptyList<String>(),
            rules.checkDependencies(listOf(Coordinates("com.microsoft.onnxruntime", "onnxruntime-android", "1.28.1"))),
        )
        assertEquals(
            listOf("com.microsoft.onnxruntime:onnxruntime-android:1.31.0 must have a version that matches 1.28.*"),
            rules.checkDependencies(listOf(Coordinates("com.microsoft.onnxruntime", "onnxruntime-android", "1.31.0"))),
        )
    }

    @Test
    fun exceptionsNeedAnExactMatch() {
        assertTrue(rules.isNetworkClassExcepted("dev/kestrel/tools/Fetcher"))
        assertFalse(rules.isNetworkClassExcepted("dev/kestrel/app/Fetcher"))
        assertTrue(rules.isNativeSymbolExcepted("libexample.so", "connect"))
        assertFalse(rules.isNativeSymbolExcepted("libexample.so", "socket"))
        assertFalse(rules.isNativeSymbolExcepted("libother.so", "connect"))
    }

    @Test
    fun malformedLineReportsItsLineNumber() {
        val error = runCatching { AuditRules.parse("allow androidx.*:* Apache-2.0\nallow-network-class dev/kestrel/") }
            .exceptionOrNull()
        assertEquals(
            "audit/rules.txt line 2: expected 'allow-network-class <class prefix> <justification>'",
            error?.message,
        )
    }

    @Test
    fun unknownRuleIsRejected() {
        val error = runCatching { AuditRules.parse("permit androidx.*:* Apache-2.0") }.exceptionOrNull()
        assertEquals("audit/rules.txt line 1: unknown rule 'permit'", error?.message)
    }
}
