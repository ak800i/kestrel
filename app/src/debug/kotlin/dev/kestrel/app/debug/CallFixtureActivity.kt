package dev.kestrel.app.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.widget.CheckBox

/**
 * A screen with a "Mute" switch, which stands for another app's control with the label of an in-call control, for the
 * call check of scripts\listen-check.ps1: during a call, "Kestrel, mute" must click the in-call screen's "Mute" and
 * never this one (design spec, section 8.7). Only the shell may start it, as only the shell may send the test hooks.
 */
class CallFixtureActivity : Activity() {
    // The English label of Google's in-call control, which the call commands look for, in every language.
    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(CheckBox(this).apply { text = "Mute" })
    }
}
