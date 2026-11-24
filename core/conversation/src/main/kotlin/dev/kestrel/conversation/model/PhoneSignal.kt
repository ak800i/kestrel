package dev.kestrel.conversation.model

/**
 * What `:app`'s `PhoneSignals` tells the conversation (design 6.5 of sub-project 7), with listening on or off. A call that
 * becomes active without ringing within 5 s of an agent's action is [AGENT_CALL] instead of [CALL_ACTIVE]: the call
 * backstop's verdict (D13 of sub-project 5), which ends the goal.
 */
enum class PhoneSignal { LOCKED, UNLOCKED, RINGING, CALL_ACTIVE, CALL_IDLE, AGENT_CALL }
