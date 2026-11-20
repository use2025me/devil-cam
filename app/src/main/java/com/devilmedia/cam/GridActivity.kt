package com.devilmedia.cam

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.concurrent.thread

/** A video tile: live picture, camera name and a status message. Selectable with a TV remote. */
@OptIn(UnstableApi::class)
class CameraTile(context: Context, val channel: Channel) : FrameLayout(context) {
    val video = PlayerView(context).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShutterBackgroundColor(Color.BLACK)
        isFocusable = false
        isClickable = false
    }
    private val status = TextView(context).apply {
        setTextColor(Brand.MUTED)
        textSize = 14f
        gravity = Gravity.CENTER
    }
    private val label = TextView(context).apply {
        text = channel.name
        setTextColor(Color.WHITE)
        textSize = 14f
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
        setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
    }
    /** Still photos from the recorder, shown until live video starts (or if it can't). */
    private val photo = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }
    var player: ExoPlayer? = null
        private set
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var photoRun = 0
    private var failures = 0

    init {
        setBackgroundColor(Color.BLACK)
        foreground = context.focusBg(Color.TRANSPARENT, 0x33FFFFFF, radius = 4)
        isFocusable = true
        isClickable = true
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(photo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        status.setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(8))
        status.setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    /** Live view: shows photos straight away, then switches to smooth video once it starts. */
    fun startLive(rec: Recorder, sub: Boolean) {
        startPhotos(rec)
        start(rec.liveUrl(channel.number, sub), rec)
    }

    fun start(url: String, rec: Recorder? = null) {
        stopPlayer()
        if (status.text.isNullOrEmpty()) status.text = "Connecting…"
        val p = Players.create(context, url, live = true)
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                failures = 0
                status.text = ""
                stopPhotos()
            }

            override fun onPlayerError(error: PlaybackException) {
                failures++
                val why = reason(error)
                status.text = if (rec != null && photo.visibility == View.VISIBLE)
                    "Live video didn't start ($why). Showing photos. Retrying…"
                else "No picture ($why). Retrying…"
                // Try again, waiting a little longer each time.
                val wait = (5000L * failures).coerceAtMost(30_000L)
                handler.postDelayed({ if (player === p) start(url, rec) }, wait)
            }
        })
        video.player = p
        player = p
    }

    private fun reason(e: PlaybackException): String {
        var c: Throwable? = e.cause
        var msg = e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
        while (c != null) {
            val m = c.message
            if (!m.isNullOrBlank()) msg = "$msg: ${m.take(80)}"
            c = if (c.cause === c) null else c.cause
            if (msg.length > 120) break
        }
        return msg
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

    private fun stopPlayer() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        video.player = null
    }

    fun stop() {
        stopPhotos()
        stopPlayer()
        status.text = ""
    }
}

/** All cameras at once. Tap (or press OK on the remote) to go full screen. */
class GridActivity : Activity() {

    private val tiles = mutableListOf<CameraTile>()
    private var recorder: Recorder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val rec = Store(this).recorder
        if (rec == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        recorder = rec
        build(rec)
        checkVideoPort(rec)
    }

    /** Older setups assumed port 554; ask the recorder which video port it really uses. */
    private fun checkVideoPort(rec: Recorder) {
        background({ Isapi(rec.baseHttp(), rec.user, rec.password).rtspPort() }) { port ->
            if (port != null && port != rec.rtspPort) {
                val updated = rec.copy(rtspPort = port)
                Store(this).recorder = updated
                recorder = updated
                startAll()
            }
        }
    }

    private fun build(rec: Recorder) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Brand.BG)
            setPadding(dp(14), dp(6), dp(8), dp(6))
        }
        bar.addView(TextView(this).apply {
            text = "Devil Cam"
            setTextColor(Color.WHITE)
            textSize = 18f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(barButton("Playback") { startActivity(Intent(this, PlaybackActivity::class.java)) })
        bar.addView(barButton("Safety") { startActivity(Intent(this, SafetyActivity::class.java)) })
        bar.addView(barButton("Settings") { settings() })
        root.addView(bar)

        val cams = rec.channels
        val cols = when {
            cams.size <= 1 -> 1
            cams.size <= 4 -> 2
            cams.size <= 9 -> 3
            else -> 4
        }
        val rows = (cams.size + cols - 1) / cols
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tiles.clear()
        for (r in 0 until rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val i = r * cols + c
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                lp.setMargins(dp(1), dp(1), dp(1), dp(1))
                if (i < cams.size) {
                    val tile = CameraTile(this, cams[i])
                    tile.setOnClickListener {
                        startActivity(Intent(this, CameraActivity::class.java).putExtra("index", i))
                    }
                    tiles += tile
                    row.addView(tile, lp)
                } else {
                    row.addView(View(this), lp)
                }
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        tiles.firstOrNull()?.requestFocus()
    }

    private fun barButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = focusBg(Brand.CARD, Brand.CARD_PRESSED)
        stateListAnimator = null
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
            marginStart = dp(6)
        }
    }

    private fun settings() {
        val rec = recorder ?: return
        val options = arrayOf("Refresh camera list", "Change recorder or login", "About Devil Cam")
        AlertDialog.Builder(this, R.style.Theme_DevilCam_Dialog)
            .setTitle("${rec.model} · ${rec.host}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> refresh(rec)
                    1 -> {
                        Store(this).recorder = null
                        startActivity(Intent(this, SetupActivity::class.java))
                        finish()
                    }
                    2 -> AlertDialog.Builder(this, R.style.Theme_DevilCam_Dialog)
                        .setTitle("Devil Cam")
                        .setMessage("Version ${packageManager.getPackageInfo(packageName, 0).versionName}\n\nA Devil Media Group app.\nWatches your cameras over your home Wi-Fi, with no ports opened on your router.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
    }

    private fun refresh(rec: Recorder) {
        background({
            try {
                Isapi(rec.baseHttp(), rec.user, rec.password).channels()
            } catch (_: Exception) {
                null
            }
        }) { chans ->
            if (chans == null) return@background
            val updated = rec.copy(channels = chans)
            Store(this).recorder = updated
            recorder = updated
            tiles.forEach { it.stop() }
            build(updated)
            startAll()
        }
    }

    private fun startAll() {
        val rec = recorder ?: return
        // Light "sub-stream" in the grid keeps several cameras smooth, even on TV boxes.
        tiles.forEach { it.startLive(rec, sub = true) }
    }

    override fun onStart() {
        super.onStart()
        startAll()
    }

    override fun onStop() {
        super.onStop()
        tiles.forEach { it.stop() }
    }
}
