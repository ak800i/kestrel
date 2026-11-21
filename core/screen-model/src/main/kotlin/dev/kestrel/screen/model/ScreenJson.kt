package dev.kestrel.screen.model

import kotlinx.serialization.json.Json

/** The JSON form of a [ScreenSnapshot]. Fields that have their default value are left out. */
object ScreenJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: ScreenSnapshot): String = json.encodeToString(ScreenSnapshot.serializer(), snapshot)

    fun decode(text: String): ScreenSnapshot = json.decodeFromString(ScreenSnapshot.serializer(), text)
}
