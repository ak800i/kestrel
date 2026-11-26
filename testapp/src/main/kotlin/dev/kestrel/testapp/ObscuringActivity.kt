package dev.kestrel.testapp

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * A window of this app over every app, through which touches pass (plan 7F): Kestrel's conversation screen drops a touch
 * that passes through another app's window (design 12.2 of sub-project 7). The window, "Obscuring overlay", stays while
 * this activity lives, in the background too, and shows only when the app-op `SYSTEM_ALERT_WINDOW` allows this app to draw
 * over other apps; the device test allows it and puts it back.
 */
class ObscuringActivity : Activity() {
    private var overlay: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Obscuring fixture")
        if (!Settings.canDrawOverlays(this)) {
            page.addView(TextView(this).apply { text = "This app may not draw over other apps." })
            return
        }
        val cover = View(this).apply { setBackgroundColor(Color.rgb(255, 160, 0)) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            alpha = ALPHA
            title = OVERLAY_TITLE
        }
        getSystemService(WindowManager::class.java).addView(cover, params)
        overlay = cover
    }

    override fun onDestroy() {
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
        super.onDestroy()
    }

    private companion object {
        /** Android lets touches pass through another app's overlay only up to opacity 0.8 (its rule for untrusted touches). */
        const val ALPHA = 0.5f
        const val OVERLAY_TITLE = "Obscuring overlay"
    }
}
