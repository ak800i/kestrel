package dev.kestrel.app.assist

import android.app.Activity
import android.os.Bundle
import dev.kestrel.app.KestrelApplication

/**
 * The earbuds' and headsets' assistant button (design spec, section 8.6; prototype P3). Android starts it for
 * VOICE_COMMAND, which Bluetooth headsets send, and for VOICE_SEARCH_HANDS_FREE, once the owner has picked Kestrel in
 * Android's chooser. It starts listening and waits for a task, as the assistant gesture does, and closes at once
 * without showing anything. While it is in front, Android lets Kestrel start its microphone service.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as KestrelApplication).graph
        graph.timeline.start("headset")
        graph.listening.assist()
        finish()
    }
}
