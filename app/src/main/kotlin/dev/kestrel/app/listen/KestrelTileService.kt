package dev.kestrel.app.listen

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.R
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Kestrel's Quick Settings tile (design spec, sections 7.2, 8.5 and 8.6). A tap toggles between listening and paused, or
 * starts Kestrel when it is off. While the owner sees the tile, it shows the mode: active while Kestrel listens, inactive
 * while it is paused or off. Android calls every callback on the main thread.
 */
class KestrelTileService : TileService() {
    private val listening: Listening get() = (application as KestrelApplication).graph.listening
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var following: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        following?.cancel()
        following = scope.launch {
            listening.state.map { it.mode }.distinctUntilChanged().collect { show(it) }
        }
    }

    override fun onStopListening() {
        following?.cancel()
        following = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        listening.send(Event.Toggle)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun show(mode: Mode) {
        val tile = qsTile ?: return
        tile.state = if (mode == Mode.LISTENING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(
            when (mode) {
                Mode.LISTENING -> R.string.tile_listening
                Mode.PAUSED -> R.string.tile_paused
                Mode.OFF -> R.string.tile_off
            },
        )
        // A small tile shows no subtitle, and TalkBack would say "Off" both while Kestrel is paused and while it is off.
        tile.stateDescription = tile.subtitle
        tile.updateTile()
    }
}
