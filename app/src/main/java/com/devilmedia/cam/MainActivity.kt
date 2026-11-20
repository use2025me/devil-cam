package com.devilmedia.cam

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Opens the camera grid if a recorder is saved, otherwise the setup screen. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = if (Store(this).recorder != null) GridActivity::class.java else SetupActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }
}
