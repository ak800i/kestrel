package dev.kestrel.build.audit

import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestScannerTest {
    @Test
    fun findsOnlyTheAppsOwnComponentsThatRequireDump() {
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.kestrel">
                <application>
                    <activity android:name="dev.kestrel.app.MainActivity" android:exported="true" />
                    <service
                        android:name="dev.kestrel.app.KestrelAccessibilityService"
                        android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" />
                    <receiver
                        android:name="dev.kestrel.app.debug.TestHookReceiver"
                        android:exported="true"
                        android:permission="android.permission.DUMP" />
                    <receiver
                        android:name="androidx.profileinstaller.ProfileInstallReceiver"
                        android:exported="true"
                        android:permission="android.permission.DUMP" />
                </application>
            </manifest>
        """.trimIndent()

        assertEquals(listOf("dev.kestrel.app.debug.TestHookReceiver"), findTestHooks(manifest, "dev.kestrel"))
    }
}
