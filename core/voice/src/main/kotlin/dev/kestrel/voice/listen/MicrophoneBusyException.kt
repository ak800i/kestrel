package dev.kestrel.voice.listen

/**
 * A [Microphone.start] that was refused before anything opened, because the thread of an earlier stop has not ended
 * yet ([MicrophoneThread]). The thread ends as soon as its current call returns, so a later start may succeed.
 */
class MicrophoneBusyException(message: String) : IllegalStateException(message)
