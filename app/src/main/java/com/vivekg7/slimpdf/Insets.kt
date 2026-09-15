package com.vivekg7.slimpdf

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * The little bit of edge-to-edge plumbing that AndroidX would otherwise provide.
 *
 * targetSdk 36 means the system draws behind the bars whether we ask for it or not, so
 * every screen has to inset its own chrome.
 */
object Insets {

    @Suppress("DEPRECATION")
    fun goEdgeToEdge(activity: Activity) {
        when {
            // With targetSdk 35+ the platform already draws edge to edge, and asking for
            // it again is deprecated.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> Unit

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                activity.window.setDecorFitsSystemWindows(false)

            else -> activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    /** Invokes [apply] with the system-bar insets, now and on every change. */
    fun onSystemBars(view: View, apply: (top: Int, bottom: Int) -> Unit) {
        view.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                apply(i.top, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                apply(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
            }
            insets
        }
        view.requestApplyInsets()
    }

    /**
     * Switches the status/navigation bar glyphs to dark-on-light or light-on-dark.
     *
     * The reader draws white pages under transparent system bars, so without this the
     * clock and battery disappear the moment the toolbar scrim is hidden.
     */
    fun darkSystemBarIcons(activity: Activity, dark: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        activity.window.insetsController?.setSystemBarsAppearance(if (dark) mask else 0, mask)
    }
}
