package dev.kestrel.conversation.machine

import dev.kestrel.agent.EndReason
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.DialogueEvent
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.local.LocalOutcome
import dev.kestrel.conversation.model.ConversationSettings
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.PhoneSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The quiet clock (design 6.2 of sub-project 7). Instant commands never reach the conversation; plan 7D's `RulesTest` holds that. */
class QuietClockTest : MachineTesting() {
    @Test
    fun theClockRunsFromTheEndOfAFinalReplyAndClosesTheConversationAfterTheQuietTime() {
        val replying = ended(acting())
        assertEquals(QuietClock.Held(HoldReason.REPLYING), replying.state.quiet)

        now += 5_000
        val running = step(replying.state, ConversationEvent.ReplyShown(1))
        assertEquals(QuietClock.Running(1, now), running.state.quiet)
        assertEquals(ConversationEffect.StartTimer(Timer.QUIET, 1, 60_000), running.effect<ConversationEffect.StartTimer>())
        assertTrue(running.has<ConversationEffect.Persist>())

        val closed = step(running.state, ConversationEvent.TimerEnded(Timer.QUIET, 1))
        assertEquals(ConversationEffect.Close(id), closed.effect<ConversationEffect.Close>())
        assertNull(closed.state.id)
        assertEquals(QuietClock.Off, closed.state.quiet)
    }

    @Test
    fun everyHoldStopsTheClockAndItsEndStartsItFromZero() {
        val open = idleOpen()

        now += 10_000
        val typing = step(open, ConversationEvent.Composing(true)).state
        assertEquals(QuietClock.Held(HoldReason.COMPOSING), typing.quiet)
        now += 10_000
        val typed = step(typing, ConversationEvent.Composing(false))
        assertEquals(QuietClock.Running(2, now), typed.state.quiet)
        assertEquals(ConversationEffect.StartTimer(Timer.QUIET, 2, 60_000), typed.effect<ConversationEffect.StartTimer>())

        val waiting = step(typed.state, ConversationEvent.Awaiting(true)).state
        assertEquals(QuietClock.Held(HoldReason.WAITING_FOR_COMMAND), waiting.quiet)
        assertEquals(QuietClock.Running(3, now), step(waiting, ConversationEvent.Awaiting(false)).state.quiet)

        assertEquals(QuietClock.Held(HoldReason.QUESTION), step(open, ConversationEvent.Dialogue(DialogueEvent.Asked(5, QuestionKind.SWITCH))).state.quiet)
        assertEquals(QuietClock.Held(HoldReason.GOAL), step(open, typed("And Bluetooth")).state.quiet)
    }

    @Test
    fun anOlderTimerChangesNothing() {
        val again = step(step(idleOpen(), ConversationEvent.Composing(true)).state, ConversationEvent.Composing(false)).state

        val old = step(again, ConversationEvent.TimerEnded(Timer.QUIET, 1))

        assertEquals(id, old.state.id)
        assertTrue(old.effects.isEmpty())
    }

    @Test
    fun locksAndCallsLeaveTheClockAlone() {
        val open = idleOpen()

        val after = listOf(PhoneSignal.LOCKED, PhoneSignal.RINGING, PhoneSignal.CALL_ACTIVE, PhoneSignal.CALL_IDLE, PhoneSignal.UNLOCKED)
            .fold(open) { state, signal -> step(state, ConversationEvent.Signal(signal)).state }

        assertEquals(open.quiet, after.quiet)
    }

    @Test
    fun aChangedQuietTimeCountsAtOnceForTheRunningClock() {
        val open = idleOpen()
        val since = (open.quiet as QuietClock.Running).sinceWall
        now += 40_000

        val longer = step(open, ConversationEvent.SettingsChanged(ConversationSettings(quietSeconds = 120), testMode = false))
        assertEquals(QuietClock.Running(2, since), longer.state.quiet)
        assertEquals(ConversationEffect.StartTimer(Timer.QUIET, 2, 80_000), longer.effect<ConversationEffect.StartTimer>())

        val shorter = step(open, ConversationEvent.SettingsChanged(ConversationSettings(quietSeconds = 30), testMode = false))
        assertEquals(ConversationEffect.Close(id), shorter.effect<ConversationEffect.Close>())
    }

    @Test
    fun theEndOfALocalRequestsReplyStartsTheClock() {
        val remembering = step(idleOpen(), typed(InputIntent.Remember("the gate code", "4321")))
        val run = remembering.effect<ConversationEffect.RunLocal>()
        assertEquals(QuietClock.Held(HoldReason.WORKING), remembering.state.quiet)

        val replied = step(remembering.state, ConversationEvent.LocalEnded(run.command, LocalOutcome.Reply(ConversationTexts.REMEMBERED)))
        assertEquals(QuietClock.Held(HoldReason.REPLYING), replied.state.quiet)
        assertEquals(ConversationEffect.EndRecord(id, 2, EndReason.FINISHED, ConversationTexts.REMEMBERED), replied.effect<ConversationEffect.EndRecord>())

        now += 1_000
        assertEquals(QuietClock.Running(2, now), step(replied.state, ConversationEvent.ReplyShown(run.command)).state.quiet)
    }
}
