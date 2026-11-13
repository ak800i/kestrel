package dev.kestrel.testapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button

/** The launcher screen. It lists the fixtures for manual use; tests start each fixture directly. */
class FixturesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Kestrel fixtures")
        val fixtures = listOf(
            "Views" to ViewsActivity::class.java,
            "Scroll" to ScrollActivity::class.java,
            "Gestures" to GesturesActivity::class.java,
            "Compose" to ComposeActivity::class.java,
            "Web" to WebActivity::class.java,
            "Secure" to SecureActivity::class.java,
            "Hook probe" to HookProbeActivity::class.java,
        )
        for ((name, activity) in fixtures) {
            page.addView(Button(this).apply {
                text = name
                setOnClickListener { startActivity(Intent(context, activity)) }
            })
        }
    }
}
