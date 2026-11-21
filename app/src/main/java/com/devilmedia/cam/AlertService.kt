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
            val store = Store(this)
            // One listener for the recorder, and one for each Hikvision doorbell or camera.
            if (store.recorder != null) thread(isDaemon = true, name = "alerts-rec") { loop(null) }
            store.devices.filter { it.canAlert }.forEach { d ->
                thread(isDaemon = true, name = "alerts-${d.id}") { loop(d.id) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    /** Listens to the recorder (deviceId null) or to one added doorbell/camera. */
    private fun loop(deviceId: String?) {
        var wait = 5_000L
        while (running) {
            val store = Store(this)
            if (!store.motionAlerts || !store.hasAnything) {
                stopSelf()
                return
            }
            val rec = store.recorder
            val dev = deviceId?.let { id -> store.devices.firstOrNull { it.id == id } }
            if ((deviceId == null && rec == null) || (deviceId != null && dev == null)) return
            try {
                val api = dev?.isapi() ?: Isapi(rec!!.baseHttp(), rec.user, rec.password)
                val c = api.openStream("/ISAPI/Event/notification/alertStream")
                wait = 5_000L
                c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val sb = StringBuilder()
                    while (running) {
                        val line = reader.readLine() ?: break
                        sb.append(line).append('\n')
                        if (line.contains("</EventNotificationAlert>")) {
                            handle(store, dev, api, sb.toString())
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

    private fun handle(store: Store, dev: Device?, api: Isapi, xml: String) {
        val type = Isapi.tag(xml, "eventType")?.lowercase() ?: return
        val state = Isapi.tag(xml, "eventState")?.lowercase() ?: "active"
        if (state != "active") return
        // A doorbell press: Hikvision doorbells report these as call/doorbell/intercom events.
        val ring = dev != null && listOf("door", "call", "ring", "intercom").any { type.contains(it) }
        // Movement, people/vehicle detection and line/area alarms all count as "motion".
        val motion = type in setOf("vmd", "linedetection", "fielddetection", "regionentrance", "regionexiting", "pir")
        if (!ring && !motion) return
        val ch = if (dev != null) 1 else
            (Isapi.tag(xml, "channelID") ?: Isapi.tag(xml, "dynChannelID"))?.toIntOrNull() ?: return
        val source: CamSource = dev ?: store.recorder?.let { r ->
            r.channels.firstOrNull { it.number == ch }?.let { RecorderCam(r, it) }
        } ?: return
        val id = source.key.hashCode()
        val now = System.currentTimeMillis()
        // Doorbell presses always alert; motion at most once a minute per camera.
        if (!ring && now - (lastAlert[id] ?: 0L) < 60_000L) return
        lastAlert[id] = now
        val photo = api.snapshot(ch)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val time = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
        val title = if (ring) "🔔 Someone's at the ${source.name.lowercase()}" else "Motion on ${source.name}"
        val b = Notification.Builder(this, ALERTS)
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentTitle(title)
            .setContentText("$time · Tap to watch live.")
            .setAutoCancel(true)
            .setCategory(if (ring) Notification.CATEGORY_CALL else Notification.CATEGORY_EVENT)
            .setContentIntent(openApp(this, source.key))
        if (photo != null) {
            b.setLargeIcon(photo)
            b.setStyle(Notification.BigPictureStyle().bigPicture(photo).setSummaryText(time))
        }
        getSystemService(NotificationManager::class.java).notify(id, b.build())
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

        private fun openApp(context: Context, key: String?): PendingIntent {
            val i = if (key != null)
                Intent(context, CameraActivity::class.java).putExtra("key", key)
            else Intent(context, GridActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context, key?.hashCode() ?: -1, i,
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
