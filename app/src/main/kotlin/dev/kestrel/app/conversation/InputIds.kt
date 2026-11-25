package dev.kestrel.app.conversation

import java.util.concurrent.atomic.AtomicLong

/** The numbers of the owner's inputs, unique in the process (design 5.3 of sub-project 7), for every channel. */
internal object InputIds {
    private val last = AtomicLong()

    fun next(): Long = last.incrementAndGet()

    /** The newest number given so far, 0 before the first. */
    fun newest(): Long = last.get()
}
