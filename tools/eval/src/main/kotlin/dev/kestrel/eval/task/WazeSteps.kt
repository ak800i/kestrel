package dev.kestrel.eval.task

import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookAnswer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/** Sends the A71's test location to Android's test providers until it is closed. */
fun interface FixSender {
    fun start(device: Device, location: String): AutoCloseable
}

/** A fresh fix every second from a background thread, as a71-agent.ps1's `Start-TestLocation` sent them from a job. */
object BackgroundFixes : FixSender {
    override fun start(device: Device, location: String): AutoCloseable {
        val running = AtomicBoolean(true)
        val sender = thread(isDaemon = true, name = "eval-test-location") {
            while (running.get()) {
                try {
                    // A lost fix needs no read-back: the next one follows a second later.
                    device.once("for p in ${TestLocation.PROVIDERS}; do cmd location providers set-test-provider-location \$p --location $location --accuracy 5; done")
                    Thread.sleep(1_000)
                } catch (e: Exception) {
                    running.set(false)
                }
            }
        }
        return AutoCloseable {
            running.set(false)
            sender.join(5_000)
        }
    }
}

/**
 * The A71's test location (a71-agent.ps1's `Start-TestLocation`): the shell as the mock-location app, with test providers
 * for gps, network and fused at `testLocation` of the local configuration, a public place a few kilometres from Home, and
 * a fresh fix every second. The restore stops the fixes, removes the providers and puts back the shell's mock-location
 * mode and the location switch as they were.
 */
class TestLocation(private val fixes: FixSender = BackgroundFixes) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val location = scope.config.testLocation?.takeIf { LOCATION.matches(it) }
            ?: return SetupResult.Untestable("corpus\\owner\\a71-config.json gives no test location")
        val mode = MODE.find(scope.device.read("appops get com.android.shell android:mock_location").joinToString("\n"))?.groupValues?.get(1) ?: "default"
        val enabled = scope.device.read("cmd location is-location-enabled").joinToString("").trim()
        val undo = "appops set com.android.shell android:mock_location allow; for p in $PROVIDERS; do cmd location providers remove-test-provider \$p; done; " +
            "appops set com.android.shell android:mock_location $mode; cmd location set-location-enabled $enabled"
        var sender: AutoCloseable? = null
        val what = "the test location on ${scope.device.serial}"
        val byHand = "Run: adb -s ${scope.device.serial} shell \"$undo\""
        scope.restores.push(what, byHand) {
            sender?.close()
            scope.device.idempotent(undo)
        }
        // Both changes go through the restores, so that after Ctrl+C neither starts once the restore above has run.
        scope.restores.change(what, byHand) {
            scope.device.idempotent(
                "appops set com.android.shell android:mock_location allow; cmd location set-location-enabled true; " +
                    "for p in $PROVIDERS; do cmd location providers add-test-provider \$p; cmd location providers set-test-provider-enabled \$p true; done",
            )
        }
        scope.restores.change(what, byHand) { sender = fixes.start(scope.device, location) }
        return SetupResult.Ready
    }

    override fun equals(other: Any?): Boolean = other is TestLocation

    override fun hashCode(): Int = TestLocation::class.hashCode()

    companion object {
        const val PROVIDERS = "gps network fused"
        private val LOCATION = Regex("^-?\\d{1,2}\\.\\d+,-?\\d{1,3}\\.\\d+$")
        private val MODE = Regex("(?m)MOCK_LOCATION: (\\w+)")
    }
}

/**
 * Whether Waze shows the test location's way Home (a71-agent.ps1's `Test-WazeHome`): Waze's deep link to the favourite
 * Home starts a navigation, whose ongoing notification shows within 30 s, only when Home is set and Waze is past its
 * first screens; Waze is stopped afterwards, which ends the navigation. Otherwise the task is not testable here.
 */
data object WazeHome : Setup {
    const val WAZE = "com.waze"
    const val HOME = "waze://?favorite=home&navigate=yes"

    override fun apply(scope: RunScope): SetupResult {
        if (!Dumps.packageListed(scope.device.read("pm list packages $WAZE"), WAZE)) return SetupResult.Untestable("Waze is not installed")
        scope.restores.push("Waze stopped", "Close Waze.") { scope.device.idempotent("am force-stop $WAZE") }
        scope.device.idempotent("am force-stop $WAZE")
        // A lost answer is read back by the notification below.
        scope.restores.change("Waze's navigation to Home", "Close Waze.") { scope.device.once("am start -a android.intent.action.VIEW -d '$HOME' -p $WAZE") }
        var navigating = false
        for (poll in 1..15) {
            scope.clock.sleep(2.seconds)
            if (Dumps.wazeNavigating(scope.device.read("dumpsys notification"))) {
                navigating = true
                break
            }
        }
        scope.device.idempotent("am force-stop $WAZE")
        return if (navigating) SetupResult.Ready else SetupResult.Untestable("Waze shows no navigation to Home at the test location")
    }
}

/** Waze navigates: `dumpsys notification` lists its ongoing notification. */
data object WazeNavigating : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean = Dumps.wazeNavigating(scope.device.read("dumpsys notification"))
}
