package dev.kestrel.voice.listen

import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.util.concurrent.Executor

/**
 * [CommunicationDevices] through Android's AudioManager (research r12). [executor] runs the watchers' calls. Android
 * keeps a request until it is cleared, the headset disconnects or Kestrel's process ends.
 */
internal class AndroidCommunicationDevices(private val audio: AudioManager, private val executor: Executor) : CommunicationDevices {
    override fun headsets(): List<Headset> = audio.availableCommunicationDevices.mapNotNull { device ->
        when (device.type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET -> Headset(device.id, leAudio = true)
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> Headset(device.id, leAudio = false)
            else -> null
        }
    }

    override fun choose(headset: Headset): Boolean {
        val device = audio.availableCommunicationDevices.firstOrNull { it.id == headset.id } ?: return false
        return audio.setCommunicationDevice(device)
    }

    override fun clear() {
        audio.clearCommunicationDevice()
    }

    override fun current(): Int? = audio.communicationDevice?.id

    override fun watch(changed: (Int?) -> Unit): AutoCloseable {
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device -> changed(device?.id) }
        audio.addOnCommunicationDeviceChangedListener(executor, listener)
        return AutoCloseable { audio.removeOnCommunicationDeviceChangedListener(listener) }
    }

    /**
     * The microphone of the LE Audio [headset], for the recording's preferred device: the input with the address of the
     * headset's output, or else the first LE Audio input. Null when Android lists none.
     */
    fun input(headset: Headset): AudioDeviceInfo? {
        val output = audio.availableCommunicationDevices.firstOrNull { it.id == headset.id } ?: return null
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        return inputs.firstOrNull { it.address == output.address } ?: inputs.firstOrNull()
    }
}
