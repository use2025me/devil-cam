package com.devilmedia.cam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Motion alerts. Keeps a connection open to the recorder's event feed and posts a
 * notification, with a photo, when a camera reports movement. Works while this device
 * is on the same Wi-Fi as the recorder.
 */
class AlertService : Service() {

    @Volatile private var running = false
    private val lastAlert = HashMap<Int, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels(this)
        val n = Notification.Builder(this, QUIET)
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentTitle("Devil Cam is watching for motion")
            .setContentText("You'll get an alert when a camera sees movement.")
            .setContentIntent(openApp(this, null))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            thread(isDaemon = true, name = "devil-cam-alerts") { loop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun loop() {
        var wait = 5_000L
        while (running) {
            val rec = Store(this).recorder
            if (rec == null || !Store(this).motionAlerts) {
                stopSelf()
                return
            }
            try {
                val api = Isapi(rec.baseHttp(), rec.user, rec.password)
                val c = api.openStream("/ISAPI/Event/notification/alertStream")
                wait = 5_000L
                c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val sb = StringBuilder()
                    while (running) {
                        val line = reader.readLine() ?: break
                        sb.append(line).append('\n')
                        if (line.contains("</EventNotificationAlert>")) {
                            handle(rec, api, sb.toString())
                            sb.setLength(0)
                        }
                        if (sb.length > 200_000) sb.setLength(0)
                    }
                }
                c.disconnect()
            } catch (_: Exception) {
                // Off the home Wi-Fi or the recorder restarted: try again shortly.
            }
            if (!running) return
            Thread.sleep(wait)
            wait = (wait * 2).coerceAtMost(120_000L)
        }
    }

    private fun handle(rec: Recorder, api: Isapi, xml: String) {
        val type = Isapi.tag(xml, "eventType")?.lowercase() ?: return
        val state = Isapi.tag(xml, "eventState")?.lowercase() ?: "active"
        if (state != "active") return
        // Movement, people/vehicle detection and line/area alarms all count as "motion".
        val motion = type in setOf("vmd", "linedetection", "fielddetection", "regionentrance", "regionexiting", "pir")
        if (!motion) return
        val ch = (Isapi.tag(xml, "channelID") ?: Isapi.tag(xml, "dynChannelID"))?.toIntOrNull() ?: return
        val now = System.currentTimeMillis()
        // At most one alert per camera per minute.
        if (now - (lastAlert[ch] ?: 0L) < 60_000L) return
        lastAlert[ch] = now
        val index = rec.channels.indexOfFirst { it.number == ch }
        val name = rec.channels.getOrNull(index)?.name ?: "Camera $ch"
        val photo = api.snapshot(ch)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val time = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
        val b = Notification.Builder(this, ALERTS)
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentTitle("Motion on $name")
            .setContentText("Seen at $time. Tap to watch live.")
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_EVENT)
            .setContentIntent(openApp(this, index.takeIf { it >= 0 }))
        if (photo != null) {
            b.setLargeIcon(photo)
            b.setStyle(Notification.BigPictureStyle().bigPicture(photo).setSummaryText("Seen at $time"))
        }
        getSystemService(NotificationManager::class.java).notify(1000 + ch, b.build())
    }

    companion object {
        private const val QUIET = "watching"
        private const val ALERTS = "motion"

        fun channels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(QUIET, "Watching for motion", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while motion alerts are on."
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(ALERTS, "Motion alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When a camera sees movement."
                }
            )
        }

        private fun openApp(context: Context, cameraIndex: Int?): PendingIntent {
            val i = if (cameraIndex != null)
                Intent(context, CameraActivity::class.java).putExtra("index", cameraIndex)
            else Intent(context, GridActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context, cameraIndex ?: -1, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun start(context: Context) {
            val i = Intent(context, AlertService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlertService::class.java))
        }
    }
}
