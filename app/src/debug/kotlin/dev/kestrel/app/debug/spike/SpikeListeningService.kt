package dev.kestrel.app.debug.spike

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * A stand-in for ListeningService (design spec, section 6) for spike S1: a foreground service of the types microphone
 * and media playback that records for a few seconds and reports what Android allowed. A background app can get
 * silence instead of an error, so it also reports, once a second, whether Android silenced the recording.
 */
class SpikeListeningService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "unknown"
        val seconds = intent?.getIntExtra(EXTRA_SECONDS, DEFAULT_SECONDS) ?: DEFAULT_SECONDS
        try {
            startForeground(NOTIFICATION_ID, notification(), FOREGROUND_TYPES)
        } catch (e: RuntimeException) {
            // From Android 14, an app that may not use the microphone in the background gets a SecurityException here.
            SpikeListening.record(this, "listen source=$source startForeground=${e.javaClass.simpleName}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        thread(name = "SpikeRecording") {
            SpikeListening.record(this, "listen source=$source ${record(seconds)}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    // The spike script grants RECORD_AUDIO before it starts the service.
    @SuppressLint("MissingPermission")
    private fun record(seconds: Int): String {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, RATE),
            )
        } catch (e: RuntimeException) {
            return "recorder=${e.javaClass.simpleName}"
        }
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return "recorder=uninitialized"
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return "recording=false"
            val buffer = ShortArray(RATE / 10)
            var frames = 0L
            var loudest = 0
            val silenced = StringBuilder()
            repeat(seconds * 10) { tenth ->
                val read = recorder.read(buffer, 0, buffer.size)
                for (i in 0 until read) loudest = maxOf(loudest, abs(buffer[i].toInt()))
                if (read > 0) frames += read
                // One letter a second: y if Android silenced the recording, n if not, ? if it did not say.
                if (tenth % 10 == 9) silenced.append(silencedLetter(recorder))
            }
            return "recording=true frames=$frames loudest=$loudest silenced=$silenced"
        } catch (e: RuntimeException) {
            return "recording=${e.javaClass.simpleName}"
        } finally {
            recorder.release()
        }
    }

    private fun silencedLetter(recorder: AudioRecord): Char = when (recorder.activeRecordingConfiguration?.isClientSilenced) {
        true -> 'y'
        false -> 'n'
        null -> '?'
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Spike recording", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Kestrel spike is recording for a few seconds")
            .build()
    }

    companion object {
        private const val RATE = 16_000
        private const val DEFAULT_SECONDS = 3
        private const val NOTIFICATION_ID = 7_001
        private const val CHANNEL = "spike-recording"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_SECONDS = "seconds"
        private const val FOREGROUND_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

        fun intent(context: Context, source: String, seconds: Int): Intent =
            Intent(context, SpikeListeningService::class.java)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_SECONDS, seconds)
    }
}
