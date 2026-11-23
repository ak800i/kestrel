package dev.kestrel.eval.config

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Refusal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * The owner's local answers that :tools:eval needs (design D2 of sub-project 6), from `corpus\owner\a71-config.json`,
 * which git ignores: the Gmail addresses of the A71 and of kestrel-api37, the A71's test location
 * ("<latitude>,<longitude>") and Waze's Home. It keeps only these four keys and never reads `pin`, which only the A71's
 * unlock function reads. A value goes only to the device, never to the console or a results file ([Redactions]).
 */
class LocalConfig private constructor(private val values: Map<String, String>) {
    val gmailAddress: String? get() = values["gmailAddress"]
    val emulatorGmailAddress: String? get() = values["emulatorGmailAddress"]
    val testLocation: String? get() = values["testLocation"]
    val homePlace: String? get() = values["homePlace"]

    /** The address of the device's own Gmail account, to which the e-mail tasks send. */
    fun address(kind: DeviceKind): String? = if (kind == DeviceKind.A71) gmailAddress else emulatorGmailAddress

    /** Every value, which [Redactions] removes from the console's text. */
    val allValues: Collection<String> get() = values.values

    override fun toString(): String = "LocalConfig(keys=${values.keys.sorted()})"

    companion object {
        val KEYS = listOf("gmailAddress", "emulatorGmailAddress", "testLocation", "homePlace")
        val EMPTY = LocalConfig(emptyMap())

        /**
         * The configuration of [file], or [EMPTY] when there is none; a key whose value is not a text is left out. A file
         * that is not strict JSON is a [Refusal] whose message holds none of the file, because the parser's error quotes it.
         */
        fun read(file: Path): LocalConfig {
            if (!Files.isRegularFile(file)) return EMPTY
            val parsed = try {
                Json.parseToJsonElement(Files.readString(file))
            } catch (e: IllegalArgumentException) {
                throw Refusal("corpus\\owner\\a71-config.json is not valid JSON (no comments or trailing commas).")
            }
            val json = parsed as? JsonObject ?: return EMPTY
            val texts = KEYS.mapNotNull { key -> (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { key to it } }
            return of(texts.toMap())
        }

        fun of(values: Map<String, String>): LocalConfig = LocalConfig(values.filterKeys { it in KEYS })
    }
}
