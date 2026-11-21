package com.devilmedia.cam

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * A camera tile: the picture, the camera's name and a LIVE badge. Selectable with a TV remote.
 * [card] gives it rounded corners and a 16:9 shape (the grid on an upright phone).
 */
class CameraTile(
    context: Context,
    val source: CamSource,
    private val card: Boolean = false,
    fill: Boolean = false,
    texture: Boolean = card
) : FrameLayout(context) {
    val video = CamVideo(context, texture = texture).also { it.fill = fill }

    /** Still photos from the recorder, shown until live video starts (or if it can't). */
    private val photo = ImageView(context).apply {
        scaleType = if (fill) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }
    private val status = TextView(context).apply {
        setTextColor(Brand.MUTED)
        textSize = 11f
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
        setPadding(context.dp(10), context.dp(2), context.dp(10), context.dp(8))
    }
    private val topShade = View(context).apply { background = shade(top = true) }
    private val name = TextView(context).apply {
        text = (if (source.doorbell) "🔔 " else "") + source.name
        setTextColor(Color.WHITE)
        textSize = 12f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val badge = context.pill("● LIVE", Brand.RED).apply { visibility = View.GONE }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var photoRun = 0
    private var failures = 0

    val isPlaying: Boolean get() = video.isActive

    init {
        setBackgroundColor(0xFF08090A.toInt())
        isFocusable = true
        isClickable = true
        val r = context.dp(if (card) 14 else 2).toFloat()
        foreground = context.focusBg(Color.TRANSPARENT, 0x22FFFFFF, radius = if (card) 14 else 2)
        if (card) {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
            }
            clipToOutline = true
        }
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(photo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(topShade, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(44), Gravity.TOP))
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(10), context.dp(8), context.dp(8), 0)
            addView(name, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(badge)
        }
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (card) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY))
        } else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Hide the name and badge (used full screen, where the screen shows them itself). */
    fun hideOverlay() {
        name.visibility = View.GONE
        badge.visibility = View.GONE
        topShade.visibility = View.GONE
        foreground = null
        overlay = false
    }
    private var overlay = true

    private fun showBadge(text: String?, fill: Int) {
        if (!overlay) return
        if (text == null) badge.visibility = View.GONE
        else {
            badge.text = text
            badge.background = context.rounded(fill, 20)
            badge.visibility = View.VISIBLE
        }
    }

    /** Live view: shows photos straight away, then switches to smooth video once it starts. */
    fun startLive(sub: Boolean) {
        startPhotos()
        play(source.liveUrl(sub), live = true)
    }

    /** Recorded video from the hard drive. */
    fun startRecording(url: String) {
        stopPhotos()
        play(url, live = false)
    }

    var onLive: ((Boolean) -> Unit)? = null

    private fun play(url: String, live: Boolean) {
        handler.removeCallbacksAndMessages(null)
        status.text = if (photo.visibility == View.VISIBLE) "" else "Connecting…"
        video.onFirstFrame = {
            failures = 0
            status.text = ""
            stopPhotos()
            if (live) showBadge("● LIVE", Brand.RED)
            onLive?.invoke(true)
        }
        video.onError = { why ->
            failures++
            showBadge(null, 0)
            onLive?.invoke(false)
            status.text = if (photo.visibility == View.VISIBLE) "Live video: $why. Retrying…" else "No picture: $why. Retrying…"
            val wait = (4000L * failures).coerceAtMost(30_000L)
            handler.postDelayed({ if (video.isActive) play(url, live) }, wait)
        }
        video.play(url, source.user, source.password, live)
    }

    private fun startPhotos() {
        val run = ++photoRun
        thread(isDaemon = true) {
            while (photoRun == run) {
                val jpg = source.snapshot()
                val bmp = jpg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (photoRun != run) break
                handler.post {
                    if (photoRun == run && bmp != null) {
                        photo.setImageBitmap(bmp)
                        photo.visibility = View.VISIBLE
                        status.text = status.text.takeIf { it != "Connecting…" } ?: ""
                        if (badge.visibility != View.VISIBLE) showBadge("PHOTO", 0xCC3A3D44.toInt())
                    }
                }
                if (jpg == null && bmp == null) Thread.sleep(10_000) else Thread.sleep(1000)
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
        showBadge(null, 0)
    }
}
