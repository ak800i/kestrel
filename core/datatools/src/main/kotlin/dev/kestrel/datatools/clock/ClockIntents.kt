package dev.kestrel.datatools.clock

import android.content.Intent
import android.provider.AlarmClock
import java.time.DayOfWeek

/**
 * `set_alarm` and `set_timer` (design D12 of sub-project 5): Android's clock intents with `EXTRA_SKIP_UI`, so that the
 * clock app sets them without showing its screen. They need the normal permission `SET_ALARM`.
 */
object ClockIntents {
    fun alarm(hour: Int, minute: Int, label: String?, days: List<DayOfWeek>): Intent =
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply {
                label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                if (days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, calendarDays(days))
            }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun timer(seconds: Int, label: String?): Intent =
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The days as `java.util.Calendar` numbers them (Sunday 1 to Saturday 7), each once, which `EXTRA_DAYS` expects. */
    fun calendarDays(days: List<DayOfWeek>): ArrayList<Int> = days.distinct().mapTo(ArrayList()) { it.value % 7 + 1 }
}
