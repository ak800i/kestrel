package dev.kestrel.datatools.navigation

import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.agent.port.TravelMode
import java.net.URLEncoder

/**
 * `navigate`'s links (design D12 of sub-project 5; research r56). Waze gets `waze://` with `favorite=home` or `work`,
 * `ll=<lat>,<lon>` or `q=<address>`, and `navigate=yes`, which since Waze 5.3 opens only the route preview: the agent then
 * taps Go. Google Maps gets `google.navigation:q=…&mode=…`, which starts turn-by-turn navigation by itself; it has no
 * home keyword, so home and work need an address alias. `geo:` is never used, because it only shows a map.
 */
object NavigationIntents {
    const val WAZE = "com.waze"
    const val GOOGLE_MAPS = "com.google.android.apps.maps"
    const val WAZE_RESULT = "Waze shows the route; tap Go to start."
    const val MAPS_RESULT = "Google Maps starts the navigation."
    const val MAPS_NEEDS_ADDRESS = "Google Maps has no home or work; ask the owner for the address."

    private const val VIEW = "android.intent.action.VIEW"
    private val COORDINATES = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")
    private val FAVOURITES = setOf("home", "work")

    fun waze(to: String): IntentSpec {
        val target = to.trim()
        val place = target.lowercase()
        val coordinates = COORDINATES.find(target)
        val query = when {
            place in FAVOURITES -> "favorite=$place"
            coordinates != null -> "ll=${coordinates.groupValues[1]},${coordinates.groupValues[2]}"
            else -> "q=${encode(target)}"
        }
        return IntentSpec(VIEW, "waze://?$query&navigate=yes", WAZE)
    }

    /** Google Maps' navigation to [to], or null for home and work, which Google Maps does not know. */
    fun googleMaps(to: String, mode: TravelMode?): IntentSpec? {
        val target = to.trim()
        if (target.lowercase() in FAVOURITES) return null
        val coordinates = COORDINATES.find(target)
        val place = if (coordinates != null) "${coordinates.groupValues[1]},${coordinates.groupValues[2]}" else encode(target)
        val letter = when (mode) {
            TravelMode.BICYCLE -> "b"
            TravelMode.WALK -> "w"
            TravelMode.TWO_WHEELER -> "l"
            TravelMode.DRIVE, null -> "d"
        }
        return IntentSpec(VIEW, "google.navigation:q=$place&mode=$letter", GOOGLE_MAPS)
    }

    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
}
