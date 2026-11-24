package dev.kestrel.datatools.clock

import android.provider.AlarmClock
import dev.kestrel.agent.port.IntentExtra
import dev.kestrel.agent.port.IntentSpec
import java.time.DayOfWeek

/**
 * `set_alarm` and `set_timer` (design D12 of sub-project 5): Android's clock intents with `EXTRA_SKIP_UI`, so that the
 * clock app sets them without showing its screen. They need the normal permission `SET_ALARM`.
 */
object ClockIntents {
    fun alarm(hour: Int, minute: Int, label: String?, days: List<DayOfWeek>): IntentSpec {
        val extras = mapOf(
            AlarmClock.EXTRA_HOUR to IntentExtra.Number(hour),
            AlarmClock.EXTRA_MINUTES to IntentExtra.Number(minute),
            AlarmClock.EXTRA_SKIP_UI to IntentExtra.Flag(true),
        ) + listOfNotNull(
            label?.let { AlarmClock.EXTRA_MESSAGE to IntentExtra.Text(it) },
            days.takeIf { it.isNotEmpty() }?.let { AlarmClock.EXTRA_DAYS to IntentExtra.Numbers(calendarDays(it)) },
        )
        return IntentSpec(AlarmClock.ACTION_SET_ALARM, extras = extras)
    }

    fun timer(seconds: Int, label: String?): IntentSpec {
        val extras = mapOf(AlarmClock.EXTRA_LENGTH to IntentExtra.Number(seconds), AlarmClock.EXTRA_SKIP_UI to IntentExtra.Flag(true)) +
            listOfNotNull(label?.let { AlarmClock.EXTRA_MESSAGE to IntentExtra.Text(it) })
        return IntentSpec(AlarmClock.ACTION_SET_TIMER, extras = extras)
    }

    /** The days as `java.util.Calendar` numbers them (Sunday 1 to Saturday 7), each once, which `EXTRA_DAYS` expects. */
    fun calendarDays(days: List<DayOfWeek>): List<Int> = days.distinct().map { it.value % 7 + 1 }
}