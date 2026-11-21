package com.devilmedia.cam

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One camera, full screen, at full quality. Left/right (or the buttons) switch camera. */
class CameraActivity : Activity() {

    private lateinit var sources: List<CamSource>
    private var index = 0
    private lateinit var tile: CameraTile
    private lateinit var frame: FrameLayout
    private lateinit var controls: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var live: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { controls.visibility = View.GONE; topBar.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val hidden = intent.getIntArrayExtra("hidden")?.toSet() ?: emptySet()
        sources = allSources(Store(this), hidden)
        if (sources.isEmpty()) return finish()
        val key = intent.getStringExtra("key")
        index = sources.indexOfFirst { it.key == key }.takeIf { it >= 0 }
            ?: intent.getIntExtra("index", 0).coerceIn(0, sources.size - 1)

        frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shade(top = true)
            setPadding(dp(12), dp(14), dp(16), dp(28))
        }
        topBar.addView(roundButton(R.drawable.ic_back, 42, 0x55000000) { finish() })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            maxLines = 1
        }
        subtitle = TextView(this).apply {
            setTextColor(0xFFD0D4DA.toInt())
            textSize = 12f
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        titles.addView(title)
        titles.addView(subtitle)
        topBar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        live = pill("● LIVE", Brand.RED).apply { visibility = View.GONE }
        topBar.addView(live)

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = shade(top = false)
            setPadding(dp(8), dp(36), dp(8), dp(22))
        }
        controls.addView(roundButton(R.drawable.ic_prev, 52) { move(-1); poke() }.spaced())
        controls.addView(roundButton(R.drawable.ic_snapshot, 66, Brand.RED) { snapshot(); poke() }.spaced())
        controls.addView(roundButton(R.drawable.ic_next, 52) { move(1); poke() }.spaced())
        setContentView(frame)
        setFullScreen(true)
        showCamera()
    }

    private fun View.spaced(): View = apply {
        (layoutParams as LinearLayout.LayoutParams).apply { marginStart = dp(14); marginEnd = dp(14) }
    }

    private fun showCamera() {
        if (::tile.isInitialized) tile.stop()
        frame.removeAllViews()
        val ch = sources[index]
        tile = CameraTile(this, ch, texture = true)
        tile.isFocusable = false
        tile.hideOverlay()
        tile.onLive = { on -> live.visibility = if (on) View.VISIBLE else View.GONE }
        live.visibility = View.GONE
        frame.addView(tile, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        title.text = ch.name
        subtitle.text = "${if (ch.doorbell) "Doorbell" else "Camera"} ${index + 1} of ${sources.size} · Full quality"
        frame.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        frame.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        tile.startLive(sub = false)
        poke()
    }

    private fun move(step: Int) {
        val n = sources.size
        index = ((index + step) % n + n) % n
        showCamera()
    }

    /** Shows the buttons for a few seconds. */
    private fun poke() {
        controls.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, 5000)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) poke()
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val wasHidden = controls.visibility != View.VISIBLE
        poke()
        if (wasHidden) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { move(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { move(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    controls.getChildAt(1)?.requestFocus()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun snapshot() {
        val ch = sources[index]
        Toast.makeText(this, "Taking a snapshot…", Toast.LENGTH_SHORT).show()
        // Devices that can't send a photo: grab the picture on screen instead.
        val frameGrab = tile.video.grabFrame()
        background({
            val jpg = ch.snapshot() ?: frameGrab ?: return@background null
            save(jpg, ch.name)
        }) { where ->
            Toast.makeText(this, if (where != null) "Saved to $where" else "Couldn't take a snapshot.", Toast.LENGTH_LONG).show()
        }
    }

    /** Saves the photo to the Pictures/Devil Cam folder. Returns where, or null. */
    private fun save(jpg: ByteArray, name: String): String? = try {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.UK).format(Date())
        val file = "${name.replace(Regex("[^A-Za-z0-9 _-]"), "")}_$stamp.jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Devil Cam")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { it.write(jpg) }
            "Pictures/Devil Cam"
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
            File(dir, file).writeBytes(jpg)
            "the Devil Cam pictures folder"
        }
    } catch (_: Exception) {
        null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setFullScreen(true)
    }

    override fun onStart() {
        super.onStart()
        if (::tile.isInitialized && !tile.isPlaying) tile.startLive(sub = false)
    }

    override fun onStop() {
        super.onStop()
        if (::tile.isInitialized) tile.stop()
    }
}
