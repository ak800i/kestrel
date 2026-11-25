package dev.kestrel.conversation.model

/**
 * What `:app`'s `PhoneSignals` tells the conversation (design 6.5 of sub-project 7), with listening on or off. A call that
 * becomes active without ringing within 5 s of an agent's action is [AGENT_CALL] instead of [CALL_ACTIVE]: the call
 * backstop's verdict (D13 of sub-project 5), which ends the goal. [SCREEN_ON] says that the screen came on, locked or not:
 * it changes nothing in the conversation, but the engine ends the timers whose deadlines passed while the phone slept, so
 * that the bar and the goal's notification never show a quiet time or a hold that has ended.
 */
enum class PhoneSignal { LOCKED, UNLOCKED, RINGING, CALL_ACTIVE, CALL_IDLE, AGENT_CALL, SCREEN_ON }
