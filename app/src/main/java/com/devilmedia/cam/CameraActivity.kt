package com.devilmedia.cam

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
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
import android.widget.Button
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

    private lateinit var rec: Recorder
    private var index = 0
    private lateinit var tile: CameraTile
    private lateinit var frame: FrameLayout
    private lateinit var controls: LinearLayout
    private lateinit var title: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { controls.visibility = View.GONE; title.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rec = Store(this).recorder ?: return finish()
        index = intent.getIntExtra("index", 0).coerceIn(0, rec.channels.size - 1)

        frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }
        controls.addView(control("◀  Previous") { move(-1) })
        controls.addView(control("Snapshot") { snapshot() })
        controls.addView(control("Next  ▶") { move(1) })
        setContentView(frame)
        showCamera()
    }

    private fun control(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = focusBg(0xCC26282D.toInt(), Brand.CARD_PRESSED)
        stateListAnimator = null
        setPadding(dp(18), 0, dp(18), 0)
        setOnClickListener { onClick(); poke() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply {
            marginStart = dp(6); marginEnd = dp(6)
        }
    }

    private fun showCamera() {
        if (::tile.isInitialized) tile.stop()
        frame.removeAllViews()
        val ch = rec.channels[index]
        tile = CameraTile(this, ch)
        tile.isFocusable = false
        tile.foreground = null
        frame.addView(tile, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        title.text = "${ch.name}   ${index + 1} of ${rec.channels.size}"
        frame.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        frame.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        tile.start(rec.liveUrl(ch.number, sub = false))
        poke()
    }

    private fun move(step: Int) {
        val n = rec.channels.size
        index = ((index + step) % n + n) % n
        showCamera()
    }

    /** Shows the buttons for a few seconds. */
    private fun poke() {
        controls.visibility = View.VISIBLE
        title.visibility = View.VISIBLE
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, 4000)
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
        val ch = rec.channels[index]
        Toast.makeText(this, "Taking a snapshot…", Toast.LENGTH_SHORT).show()
        background({
            val jpg = Isapi(rec.baseHttp(), rec.user, rec.password).snapshot(ch.number) ?: return@background null
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

    override fun onStart() {
        super.onStart()
        if (::tile.isInitialized && tile.player == null) tile.start(rec.liveUrl(rec.channels[index].number, sub = false))
    }

    override fun onStop() {
        super.onStop()
        if (::tile.isInitialized) tile.stop()
    }
}
