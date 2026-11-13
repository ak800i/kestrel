package dev.kestrel.testapp

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button

/** A window with FLAG_SECURE. Screenshots of it fail, but its accessibility tree can still be read (B10). */
class SecureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val page = fixturePage("Secure fixture")
        page.addView(Button(this).apply { text = "Secret action" })
    }
}
