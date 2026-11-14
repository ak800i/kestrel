package dev.kestrel.app.screen

import android.app.UiAutomation
import android.hardware.input.InputManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay

/**
 * A virtual touchscreen, made with Android's `uinput` shell tool, that touches the screen like the owner's finger.
 * Unlike `input tap`, its touches come from a touchscreen device and pass through Android's accessibility input
 * filter. The commands use numbers rather than names, because the tool on Android 13 reads only numbers.
 */
class TestFinger(automation: UiAutomation) : AutoCloseable {
    // The tool's output, its input and its errors.
    private val pipes = automation.executeShellCommandRwe("uinput -")
    private val commands = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).bufferedWriter()
    private var touches = 0

    init {
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val screen = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            send(register(screen.width() - 1, screen.height() - 1))
            val input = context.getSystemService(InputManager::class.java)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (input.inputDeviceIds.none { input.getInputDevice(it)?.name == NAME }) {
                check(SystemClock.uptimeMillis() < deadline) {
                    "The virtual touchscreen did not appear within 5 s. The uinput tool said: ${errors()}"
                }
                Thread.sleep(50)
            }
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /** Touches the screen at ([x], [y]) for [holdMillis], then lifts the finger. */
    suspend fun tap(x: Int, y: Int, holdMillis: Long = 50) {
        touches++
        inject(EV_ABS, ABS_MT_SLOT, 0, EV_ABS, ABS_MT_TRACKING_ID, touches, EV_ABS, ABS_MT_POSITION_X, x, EV_ABS, ABS_MT_POSITION_Y, y,
            EV_ABS, ABS_MT_PRESSURE, 100, EV_KEY, BTN_TOUCH, 1, EV_SYN, SYN_REPORT, 0)
        delay(holdMillis)
        inject(EV_ABS, ABS_MT_TRACKING_ID, -1, EV_KEY, BTN_TOUCH, 0, EV_SYN, SYN_REPORT, 0)
        delay(LIFT_MILLIS)
    }

    /** Removes the virtual touchscreen: the tool removes its device when its input ends. */
    override fun close() {
        try {
            commands.close()
        } finally {
            pipes[0].close()
            pipes[2].close()
        }
    }

    /** What the tool has written to its error output so far, without waiting for more. */
    private fun errors(): String {
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pipes[2].dup())
        return stream.use { String(it.readNBytes(it.available())).trim() }
    }

    private fun inject(vararg events: Int) = send("""{"id":1,"command":"inject","events":[${events.joinToString(",")}]}""")

    private fun send(command: String) {
        commands.write(command)
        commands.newLine()
        commands.flush()
    }

    private companion object {
        const val NAME = "Kestrel test finger"
        const val LIFT_MILLIS = 50L

        // Linux input event codes, from linux/input-event-codes.h and linux/uinput.h.
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_ABS = 3
        const val SYN_REPORT = 0
        const val BTN_TOUCH = 0x14a
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        const val ABS_MT_PRESSURE = 0x3a
        const val UI_SET_EVBIT = 100
        const val UI_SET_KEYBIT = 101
        const val UI_SET_ABSBIT = 103
        const val UI_SET_PROPBIT = 110
        const val INPUT_PROP_DIRECT = 1

        fun axis(code: Int, maximum: Int) =
            """{"code":$code,"info":{"value":0,"minimum":0,"maximum":$maximum,"fuzz":0,"flat":0,"resolution":0}}"""

        fun register(maxX: Int, maxY: Int): String {
            val configuration = listOf(
                """{"type":$UI_SET_EVBIT,"data":[$EV_KEY,$EV_ABS]}""",
                """{"type":$UI_SET_KEYBIT,"data":[$BTN_TOUCH]}""",
                """{"type":$UI_SET_ABSBIT,"data":[$ABS_MT_SLOT,$ABS_MT_POSITION_X,$ABS_MT_POSITION_Y,$ABS_MT_TRACKING_ID,$ABS_MT_PRESSURE]}""",
                """{"type":$UI_SET_PROPBIT,"data":[$INPUT_PROP_DIRECT]}""",
            )
            val axes = listOf(
                axis(ABS_MT_SLOT, 9),
                axis(ABS_MT_POSITION_X, maxX),
                axis(ABS_MT_POSITION_Y, maxY),
                axis(ABS_MT_TRACKING_ID, 65535),
                axis(ABS_MT_PRESSURE, 255),
            )
            return """{"id":1,"command":"register","name":"$NAME","vid":6353,"pid":43981,"bus":"usb",""" +
                """"configuration":[${configuration.joinToString(",")}],"abs_info":[${axes.joinToString(",")}]}"""
        }
    }
}
