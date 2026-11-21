package com.devilmedia.cam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Turns motion alerts back on after the device restarts or Devil Cam updates. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)
        if (store.motionAlerts && store.recorder != null) {
            try {
                AlertService.start(context)
            } catch (_: Exception) {
            }
        }
    }
}
