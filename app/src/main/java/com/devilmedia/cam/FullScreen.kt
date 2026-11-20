package com.devilmedia.cam

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/** Hides the phone's status and navigation bars (swipe from the edge to bring them back). */
fun Activity.setFullScreen(on: Boolean) {
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        val c = window.insetsController ?: return
        if (on) {
            c.hide(WindowInsets.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsets.Type.systemBars())
        }
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (on)
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        else View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}

val Activity.isSideways: Boolean
    get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
