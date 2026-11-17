package dev.kestrel.voice

import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.ModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The engines find their files by the manifest's paths, so each path that they use must be in the manifest. */
class VoiceModelPathsTest {
    private val manifest = ModelManifest.parse(File("../../models/manifest.json").readText())

    private fun engineOf(path: String): Engine = manifest.models.single { model -> model.files.any { it.path == path } }.engine

    @Test
    fun theSpeechDetectorsModelIsInTheManifest() {
        assertEquals(Engine.VAD, engineOf(VoiceEngines.VAD_MODEL))
    }

    @Test
    fun theVoicesFilesAreInTheManifest() {
        assertEquals(Engine.TTS, engineOf(VoiceEngines.TTS_MODEL))
        assertEquals(Engine.TTS, engineOf(VoiceEngines.TTS_TOKENS))
        // The voice uses espeak-ng's voice "en".
        assertEquals(Engine.TTS, engineOf("${VoiceEngines.ESPEAK_DATA}/lang/gmw/en"))
    }

    @Test
    fun theKokoroVoicesFilesAreInTheManifestAndOnlyItIsOptional() {
        for (path in listOf(VoiceEngines.KOKORO_MODEL, VoiceEngines.KOKORO_VOICES, VoiceEngines.KOKORO_TOKENS)) {
            assertEquals(path, Engine.TTS, engineOf(path))
        }
        val ids = manifest.models.map { it.id }.toSet()
        for (id in VoiceEngines.PIPER_MODELS + VoiceEngines.KOKORO_MODELS) assertTrue(id, id in ids)
        assertEquals(listOf("tts-kokoro"), manifest.models.filter { it.optional }.map { it.id })
    }

    @Test
    fun theKeywordSpottersFilesAreInTheManifest() {
        for (path in listOf(VoiceEngines.KWS_ENCODER, VoiceEngines.KWS_DECODER, VoiceEngines.KWS_JOINER, VoiceEngines.KWS_TOKENS)) {
            assertEquals(path, Engine.KWS, engineOf(path))
        }
    }

    @Test
    fun theRecognizersFilesAreInTheManifest() {
        for (path in listOf(VoiceEngines.ASR_ENCODER, VoiceEngines.ASR_DECODER, VoiceEngines.ASR_JOINER, VoiceEngines.ASR_TOKENS)) {
            assertEquals(path, Engine.ASR, engineOf(path))
        }
    }
}
