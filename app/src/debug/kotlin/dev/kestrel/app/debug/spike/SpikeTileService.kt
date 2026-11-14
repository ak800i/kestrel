package dev.kestrel.app.debug.spike

import android.service.quicksettings.TileService

/** A Quick Settings tile for spike S1 that starts a short recording when it is tapped. */
class SpikeTileService : TileService() {
    override fun onClick() {
        SpikeListening.start(applicationContext, source = "tile", seconds = 3)
    }
}
