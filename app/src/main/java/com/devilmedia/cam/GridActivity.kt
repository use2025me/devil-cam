package com.devilmedia.cam

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** All cameras at once. Tap (or press OK on the remote) to go full screen. */
class GridActivity : Activity() {

    private val tiles = mutableListOf<CameraTile>()
    private var recorder: Recorder? = null
    private var bar: View? = null

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
        UpdateChecker(this).check(manual = false)
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
            setPadding(dp(12), dp(4), dp(6), dp(4))
        }
        this.bar = bar
        bar.addView(TextView(this).apply {
            text = "Devil Cam"
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
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
        applyOrientation()
        tiles.firstOrNull()?.requestFocus()
    }

    private fun barButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        background = focusBg(Brand.CARD, Brand.CARD_PRESSED)
        stateListAnimator = null
        minHeight = dp(34)
        minimumHeight = dp(34)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
            marginStart = dp(6)
        }
    }

    private fun settings() {
        val rec = recorder ?: return
        val options = arrayOf("Refresh camera list", "Change recorder or login", "Check for app updates", "About Devil Cam")
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
                    2 -> UpdateChecker(this).check(manual = true)
                    3 -> AlertDialog.Builder(this, R.style.Theme_DevilCam_Dialog)
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

    /** Turn the phone sideways: the cameras fill the whole screen. Upright: the menu bar comes back. */
    private fun applyOrientation() {
        val side = isSideways
        bar?.visibility = if (side) View.GONE else View.VISIBLE
        setFullScreen(side)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
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
