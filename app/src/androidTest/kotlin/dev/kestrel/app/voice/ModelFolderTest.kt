package dev.kestrel.app.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.voice.models.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelFolderTest {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    // scripts\push-models.ps1 copies the models into this folder. On Android 17, Kestrel can read a folder that adb
    // creates only after a chmod, so Kestrel creates the folder itself (design spec, section 8.10). The test also passes
    // with a folder that an earlier install left, so it shows that the folder is there, not that Kestrel created it.
    @Test
    fun kestrelCreatesItsModelsFolder() {
        val folder = graph.models.root
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!folder.isDirectory && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)

        assertTrue(folder.path, folder.isDirectory)
        assertTrue(folder.path, folder.path.endsWith("/Android/data/dev.kestrel/files/models"))
    }

    @Test
    fun theManifestComesWithTheApp() {
        assertEquals(Engine.entries.toSet(), graph.models.manifest.models.map { it.engine }.toSet())
    }
}
