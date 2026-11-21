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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** All cameras at once. Tap (or press OK on the remote) to go full screen. */
class GridActivity : Activity() {

    private val tiles = mutableListOf<CameraTile>()
    private var recorder: Recorder? = null
    /** Cameras the recorder gets no picture from. */
    private var noSignal: Set<Int> = emptySet()

    private fun hiddenChannels(): Set<Int> = if (Store(this).hideNoSignal) noSignal else emptySet()

    /** Everything to show: recorder cameras (minus ones with no signal, if that's on), then doorbells and cameras. */
    private fun shown(): List<CamSource> = allSources(Store(this), hiddenChannels())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val store = Store(this)
        if (!store.hasAnything) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        recorder = store.recorder
        build()
        recorder?.let { checkVideoPort(it); checkSignal(it) }
        if (Store(this).motionAlerts) AlertService.start(this)
        UpdateChecker(this).check(manual = false)
    }

    private fun checkSignal(rec: Recorder) {
        background({ Isapi(rec.baseHttp(), rec.user, rec.password).noSignalChannels() }) { dead ->
            if (dead != noSignal) {
                noSignal = dead
                build()
                startAll()
            }
        }
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

    private val isTv: Boolean
        get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

    /**
     * Upright phone: header, cameras as rounded 16:9 cards, and a bottom menu.
     * Sideways phone: cameras fill the whole screen. TV: header with menu, cameras fill the rest.
     */
    private fun build() {
        tiles.forEach { it.stop() }
        tiles.clear()
        val side = isSideways && !isTv
        val cards = !side && !isTv
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (side) Color.BLACK else Brand.BG_DEEP)
        }
        if (!side) root.addView(header())

        val cams = shown()
        val fill = Store(this).fillCards
        val cols = when {
            cams.size <= 1 -> 1
            cards || cams.size <= 4 -> 2
            cams.size <= 9 -> 3
            else -> 4
        }
        val rows = (cams.size + cols - 1) / cols
        val gap = if (side) dp(1) else dp(5)
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = if (side) 0 else dp(9)
            setPadding(pad, if (side) 0 else dp(4), pad, pad)
        }
        for (r in 0 until rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val i = r * cols + c
                val lp = LinearLayout.LayoutParams(0, if (cards) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                lp.setMargins(gap, gap, gap, gap)
                if (i < cams.size) {
                    val tile = CameraTile(this, cams[i], card = cards, fill = fill && cards)
                    val key = cams[i].key
                    tile.setOnClickListener {
                        startActivity(Intent(this, CameraActivity::class.java)
                            .putExtra("key", key)
                            .putExtra("hidden", hiddenChannels().toIntArray()))
                    }
                    tiles += tile
                    row.addView(tile, lp)
                } else {
                    row.addView(View(this), lp)
                }
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (cards) ViewGroup.LayoutParams.WRAP_CONTENT else 0,
                if (cards) 0f else 1f
            ))
        }
        if (cards) {
            val scroll = ScrollView(this).apply { isFillViewport = true; addView(grid) }
            root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(bottomMenu())
        } else {
            root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        setFullScreen(side)
        tiles.firstOrNull()?.requestFocus()
    }

    private fun header(): View {
        val rec = recorder
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(10))
        }
        h.addView(android.widget.FrameLayout(this).apply {
            background = rounded(0x33C8102E, 12)
            addView(icon(R.drawable.ic_videocam, 22, 0xFFFF4D5E.toInt()),
                android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "Devil Cam"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        })
        titles.addView(TextView(this).apply {
            val list = shown()
            val n = list.size
            val bells = list.count { it.doorbell }
            val hidden = (rec?.channels?.size ?: 0) - list.count { it is RecorderCam }
            text = (rec?.model?.ifBlank { "Recorder" } ?: "Devil Cam") + " · $n camera${if (n == 1) "" else "s"}" +
                (if (bells > 0) " incl. $bells doorbell${if (bells == 1) "" else "s"}" else "") +
                if (hidden > 0) " · $hidden with no signal hidden" else ""
            setTextColor(Brand.MUTED)
            textSize = 12f
            maxLines = 1
        })
        h.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (isTv) {
            h.addView(chip(R.drawable.ic_history, "Playback") { openPlayback() })
            h.addView(chip(R.drawable.ic_shield, "Safety") { openSafety() })
            h.addView(chip(R.drawable.ic_tune, "Settings") { settings() })
        } else {
            h.addView(pill("● ONLINE", 0x223FB984, Brand.GREEN))
        }
        return h
    }

    private fun chip(iconRes: Int, label: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = focusBg(Brand.CARD, Brand.CARD_PRESSED, radius = 20)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        setPadding(dp(14), dp(8), dp(16), dp(8))
        addView(icon(iconRes, 18).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        addView(TextView(context).apply { text = label; setTextColor(Color.WHITE); textSize = 14f })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        }
    }

    private fun bottomMenu(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Brand.BG)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        fun item(iconRes: Int, label: String, onClick: () -> Unit) {
            val it = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = focusBg(Brand.BG, Brand.CARD_PRESSED, radius = 12)
                isFocusable = true
                isClickable = true
                setOnClickListener { onClick() }
                setPadding(0, dp(6), 0, dp(6))
                addView(icon(iconRes, 22, Brand.MUTED))
                addView(TextView(context).apply {
                    text = label
                    setTextColor(Brand.MUTED)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(3), 0, 0)
                })
            }
            bar.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        item(R.drawable.ic_history, "Playback") { openPlayback() }
        item(R.drawable.ic_shield, "Safety") { openSafety() }
        item(R.drawable.ic_tune, "Settings") { settings() }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(View(this).apply { setBackgroundColor(Brand.LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        wrap.addView(bar)
        return wrap
    }

    private fun openPlayback() = startActivity(Intent(this, PlaybackActivity::class.java))
    private fun openSafety() = startActivity(Intent(this, SafetyActivity::class.java))

    private fun settings() {
        val rec = recorder
        val store = Store(this)
        fun onOff(b: Boolean) = if (b) "On" else "Off"
        val options = arrayOf(
            "Motion alerts: ${onOff(store.motionAlerts)}",
            "Hide cameras with no signal: ${onOff(store.hideNoSignal)}",
            "Fill the camera cards: ${onOff(store.fillCards)}",
            "Add a doorbell or camera",
            "Remove a doorbell or camera",
            "Refresh camera list",
            if (rec != null) "Change recorder or login" else "Add a recorder",
            "Check for app updates",
            "About Devil Cam"
        )
        AlertDialog.Builder(this, R.style.Theme_DevilCam_Dialog)
            .setTitle(if (rec != null) "${rec.model} · ${rec.host}" else "Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleAlerts()
                    1 -> { store.hideNoSignal = !store.hideNoSignal; build(); startAll() }
                    2 -> { store.fillCards = !store.fillCards; build(); startAll() }
                    3 -> startActivity(Intent(this, AddDeviceActivity::class.java))
                    4 -> removeDevice()
                    5 -> rec?.let { refresh(it) }
                    6 -> {
                        if (rec != null) {
                            store.recorder = null
                            recorder = null
                        }
                        startActivity(Intent(this, SetupActivity::class.java).putExtra("addRecorder", true))
                        finish()
                    }
                    7 -> UpdateChecker(this).check(manual = true)
                    8 -> AlertDialog.Builder(this, R.style.Theme_DevilCam_Dialog)
                        .setTitle("Devil Cam")
                        .setMessage("Version ${packageManager.getPackageInfo(packageName, 0).versionName}\n\nA Devil Media Group app.\nWatches your cameras over your home Wi-Fi, with no ports opened on your router.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
    }

    private fun removeDevice() {
        val store = Store(this)
        val devices = store.devices
        if (devices.isEmpty()) {
            android.widget.Toast.makeText(this, "No doorbells or cameras added yet.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, R.style.Theme_DevilCam_Dialog)
            .setTitle("Remove which one?")
            .setItems(devices.map { it.name }.toTypedArray()) { _, i ->
                store.devices = devices.filterIndexed { j, _ -> j != i }
                if (!store.hasAnything) {
                    startActivity(Intent(this, SetupActivity::class.java))
                    finish()
                } else {
                    build(); startAll()
                }
            }
            .show()
    }

    private fun toggleAlerts() {
        val store = Store(this)
        if (store.motionAlerts) {
            store.motionAlerts = false
            AlertService.stop(this)
            android.widget.Toast.makeText(this, "Motion alerts off", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
            return
        }
        store.motionAlerts = true
        AlertService.start(this)
        AlertDialog.Builder(this, R.style.Theme_DevilCam_Dialog)
            .setTitle("Motion alerts on")
            .setMessage("You'll get an alert with a photo when a camera sees movement, while this device is on your home Wi-Fi.\n\nMotion detection must be switched on in the recorder: on the TV go to Configuration → Event → Motion, tick each camera, and under Linkage Action tick \"Notify Surveillance Center\".")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) toggleAlerts()
            else android.widget.Toast.makeText(this, "Devil Cam needs permission to show alerts.", android.widget.Toast.LENGTH_LONG).show()
        }
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
            build()
            startAll()
            checkSignal(updated)
        }
    }

    private fun startAll() {
        // Light "sub-stream" in the grid keeps several cameras smooth, even on TV boxes.
        tiles.forEach { it.startLive(sub = true) }
    }

    /** Turning the phone sideways or upright rebuilds the layout to suit. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        build()
        startAll()
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
