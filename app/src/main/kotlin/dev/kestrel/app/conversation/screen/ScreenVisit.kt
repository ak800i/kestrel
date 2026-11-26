package dev.kestrel.app.conversation.screen

import androidx.compose.runtime.compositionLocalOf

/**
 * The number of the conversation screen's current visit in front ([ScreenFeed.visits]), which the conversation activity
 * provides to everything that it draws: a card's buttons arm again on each visit (review of plan 7F's Task 7).
 */
internal val LocalScreenVisit = compositionLocalOf { 0 }
