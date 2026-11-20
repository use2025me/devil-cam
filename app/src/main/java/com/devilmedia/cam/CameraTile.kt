package com.devilmedia.cam

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.concurrent.thread

/** A video tile: live picture, camera name and a short status line. Selectable with a TV remote. */
class CameraTile(context: Context, val channel: Channel) : FrameLayout(context) {
    val video = CamVideo(context)

    /** Still photos from the recorder, shown until live video starts (or if it can't). */
    private val photo = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }
    private val status = TextView(context).apply {
        setTextColor(Brand.MUTED)
        textSize = 11f
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
        setPadding(context.dp(6), context.dp(2), context.dp(6), context.dp(6))
    }
    private val label = TextView(context).apply {
        text = channel.name
        setTextColor(Color.WHITE)
        textSize = 12f
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
        setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
    }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var photoRun = 0
    private var failures = 0

    val isPlaying: Boolean get() = video.isActive

    init {
        setBackgroundColor(Color.BLACK)
        foreground = context.focusBg(Color.TRANSPARENT, 0x33FFFFFF, radius = 4)
        isFocusable = true
        isClickable = true
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(photo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
    }

    /** Live view: shows photos straight away, then switches to smooth video once it starts. */
    fun startLive(rec: Recorder, sub: Boolean) {
        startPhotos(rec)
        play(rec.liveUrl(channel.number, sub), rec, live = true)
    }

    /** Recorded video from the hard drive. */
    fun startRecording(rec: Recorder, url: String) {
        stopPhotos()
        play(url, rec, live = false)
    }

    private fun play(url: String, rec: Recorder, live: Boolean) {
        handler.removeCallbacksAndMessages(null)
        status.text = "Connecting…"
        video.onFirstFrame = {
            failures = 0
            status.text = ""
            stopPhotos()
        }
        video.onError = { why ->
            failures++
            status.text = if (photo.visibility == View.VISIBLE) "Showing photos. Live video: $why. Retrying…"
            else "No picture: $why. Retrying…"
            val wait = (4000L * failures).coerceAtMost(30_000L)
            handler.postDelayed({ if (video.isActive) play(url, rec, live) }, wait)
        }
        video.play(url, rec.user, rec.password, live)
    }

    private fun startPhotos(rec: Recorder) {
        val run = ++photoRun
        val api = Isapi(rec.baseHttp(), rec.user, rec.password)
        thread(isDaemon = true) {
            while (photoRun == run) {
                val jpg = api.snapshot(channel.number)
                val bmp = jpg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (photoRun != run) break
                handler.post {
                    if (photoRun == run && bmp != null) {
                        photo.setImageBitmap(bmp)
                        photo.visibility = View.VISIBLE
                    }
                }
                Thread.sleep(if (bmp == null) 3000 else 1000)
            }
        }
    }

    private fun stopPhotos() {
        photoRun++
        photo.visibility = View.GONE
        photo.setImageDrawable(null)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        stopPhotos()
        video.stop()
        status.text = ""
    }
}
