package dev.kestrel.voice.models

import android.content.res.AssetManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The engines whose models Kestrel copies from this computer: the speech engines (design spec, section 8.2) and the OCR
 * of the agent's look_closer (design D20 of sub-project 5). Every value of the manifest must be here, because an
 * unknown value stops the whole manifest from parsing.
 */
@Serializable
enum class Engine {
    @SerialName("vad") VAD,
    @SerialName("kws") KWS,
    @SerialName("asr") ASR,
    @SerialName("tts") TTS,
    @SerialName("ocr") OCR,
}

/**
 * One file of a model: its [path] below Kestrel's folder `files/models`, its [size] in bytes and its [sha256] hash in
 * lowercase hex. [url], or [archive] and [member], say where scripts\push-models.ps1 gets the file; the app ignores them.
 */
@Serializable
data class ModelFile(
    val path: String,
    val size: Long,
    val sha256: String,
    val url: String? = null,
    val archive: String? = null,
    val member: String? = null,
)

/**
 * A model that one of the engines needs. An engine works when every model of its [engine] that it needs is ready.
 * [optional]: Kestrel works without the model, such as the Kokoro voice, which scripts\push-models.ps1 copies only with
 * -Kokoro; an engine needs such a model only when it is asked for by its id.
 */
@Serializable
data class Model(
    val id: String,
    val engine: Engine,
    val name: String,
    val licence: String,
    val project: String,
    val files: List<ModelFile>,
    val optional: Boolean = false,
)

/** A download that holds several model files, which scripts\push-models.ps1 unpacks. */
@Serializable
data class Archive(val id: String, val url: String, val size: Long, val sha256: String)

/** The repository's `models/manifest.json`, which the build copies into the app's assets (design spec, section 8.10). */
@Serializable
data class ModelManifest(val format: Int, val archives: List<Archive> = emptyList(), val models: List<Model>) {
    companion object {
        /** The manifest's path in the app's assets. */
        const val ASSET = "models/manifest.json"

        // A newer manifest may have fields that this version of Kestrel does not know.
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ModelManifest = json.decodeFromString(serializer(), text)

        fun fromAssets(assets: AssetManager): ModelManifest = parse(assets.open(ASSET).bufferedReader().use { it.readText() })
    }
}
