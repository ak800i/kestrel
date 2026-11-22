package dev.kestrel.datatools.navigation

import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.agent.port.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationIntentsTest {
    private val view = "android.intent.action.VIEW"

    @Test
    fun wazeGetsItsFavouritesCoordinatesOrASearch() {
        assertEquals(IntentSpec(view, "waze://?favorite=home&navigate=yes", "com.waze"), NavigationIntents.waze("Home"))
        assertEquals("waze://?favorite=work&navigate=yes", NavigationIntents.waze(" work ").data)
        assertEquals("waze://?ll=44.8231,20.4511&navigate=yes", NavigationIntents.waze("44.8231, 20.4511").data)
        assertEquals("waze://?q=Kneza%20Mihaila%201%2C%20Beograd&navigate=yes", NavigationIntents.waze("Kneza Mihaila 1, Beograd").data)
    }

    @Test
    fun googleMapsNavigatesToAPlaceWithItsModeButHasNoHome() {
        assertEquals(
            IntentSpec(view, "google.navigation:q=44.8231,20.4511&mode=w", "com.google.android.apps.maps"),
            NavigationIntents.googleMaps("44.8231,20.4511", TravelMode.WALK),
        )
        assertEquals("google.navigation:q=Kneza%20Mihaila%201&mode=d", NavigationIntents.googleMaps("Kneza Mihaila 1", null)?.data)
        assertEquals("google.navigation:q=Knez&mode=l", NavigationIntents.googleMaps("Knez", TravelMode.TWO_WHEELER)?.data)
        assertNull(NavigationIntents.googleMaps("home", TravelMode.DRIVE))
        assertNull(NavigationIntents.googleMaps("Work", null))
    }
}
