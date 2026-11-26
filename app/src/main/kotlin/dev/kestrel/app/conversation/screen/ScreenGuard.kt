package dev.kestrel.app.conversation.screen

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Design 12.2 of sub-project 7: a window of Kestrel's conversation screen drops a touch that passed through another app's
 * window over it, and from Android 14 only accessibility tools may act on its views, which inherit this from [root].
 */
internal object ScreenGuard {
    fun guard(root: View) {
        root.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) root.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }
}

/** Guards the window that holds this composable: a dialog's or a menu's, which are windows of their own (design 12.2). */
@Composable
internal fun GuardWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        ScreenGuard.guard(view.rootView)
        onDispose {}
    }
}
