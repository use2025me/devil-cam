package com.devilmedia.cam

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Watch back what the recorder saved on its hard drive. */
class PlaybackActivity : Activity() {

    private lateinit var rec: Recorder
    private var camIndex = 0
    private val start: Calendar = Calendar.getInstance().apply {
        add(Calendar.HOUR_OF_DAY, -1)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    private lateinit var videoBox: FrameLayout
    private lateinit var info: TextView
    private lateinit var camButton: Button
    private lateinit var dayButton: Button
    private lateinit var timeButton: Button
    private lateinit var pauseButton: Button
    private var tile: CameraTile? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateInfo()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rec = Store(this).recorder ?: return finish()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        videoBox = FrameLayout(this)
        root.addView(videoBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        info = TextView(this).apply {
            setTextColor(Brand.MUTED)
            textSize = 14f
            setPadding(dp(14), dp(6), dp(14), dp(2))
            setBackgroundColor(Brand.BG)
        }
        root.addView(info)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(10))
        }
        camButton = button("") { pickCamera() }
        dayButton = button("") { pickDay() }
        timeButton = button("") { pickTime() }
        row.addView(camButton); row.addView(dayButton); row.addView(timeButton)
        row.addView(button("−5 min") { shift(-5) })
        pauseButton = button("Pause") { togglePause() }
        row.addView(pauseButton)
        row.addView(button("+5 min") { shift(5) })
        val scroll = HorizontalScrollView(this).apply {
            setBackgroundColor(Brand.BG)
            isFillViewport = true
            addView(row)
        }
        root.addView(scroll)
        setContentView(root)
        play()
        camButton.requestFocus()
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = focusBg(Brand.CARD, Brand.CARD_PRESSED)
        stateListAnimator = null
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply {
            marginStart = dp(4); marginEnd = dp(4)
        }
    }

    private fun play() {
        tile?.stop()
        videoBox.removeAllViews()
        val ch = rec.channels[camIndex]
        val t = CameraTile(this, ch).apply { isFocusable = false; foreground = null }
        videoBox.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Hikvision recorders read this time as their own local time.
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).format(start.time)
        t.startRecording(rec, rec.playbackUrl(ch.number, stamp))
        tile = t
        pauseButton.text = "Pause"
        camButton.text = ch.name
        dayButton.text = dayLabel(start)
        timeButton.text = SimpleDateFormat("HH:mm", Locale.UK).format(start.time)
        updateInfo()
    }

    private fun updateInfo() {
        val pos = tile?.video?.positionMs ?: 0L
        val now = (start.clone() as Calendar).apply { add(Calendar.MILLISECOND, pos.toInt()) }
        info.text = "Recording from ${dayLabel(now)} at ${SimpleDateFormat("HH:mm:ss", Locale.UK).format(now.time)}"
    }

    private fun dayLabel(c: Calendar): String {
        val today = Calendar.getInstance()
        val days = ((startOfDay(today) - startOfDay(c)) / 86_400_000L).toInt()
        return when (days) {
            0 -> "Today"
            1 -> "Yesterday"
            else -> SimpleDateFormat("EEE d MMM", Locale.UK).format(c.time)
        }
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dialog() = AlertDialog.Builder(this, R.style.Theme_DevilCam_Dialog)

    private fun pickCamera() {
        dialog().setTitle("Camera")
            .setItems(rec.channels.map { it.name }.toTypedArray()) { _, i -> camIndex = i; keepPosition(); play() }
            .show()
    }

    private fun pickDay() {
        val days = (0 until 30).map { Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -it) } }
        dialog().setTitle("Day")
            .setItems(days.map { dayLabel(it) }.toTypedArray()) { _, i ->
                val d = days[i]
                start.set(d.get(Calendar.YEAR), d.get(Calendar.MONTH), d.get(Calendar.DAY_OF_MONTH))
                play()
            }
            .show()
    }

    private fun pickTime() {
        val hours = (0..23).map { "%02d:00".format(it) }.toTypedArray()
        dialog().setTitle("Hour").setItems(hours) { _, h ->
            val mins = arrayOf(":00", ":10", ":20", ":30", ":40", ":50").map { "%02d$it".format(h) }.toTypedArray()
            dialog().setTitle("Minute").setItems(mins) { _, m ->
                start.set(Calendar.HOUR_OF_DAY, h)
                start.set(Calendar.MINUTE, m * 10)
                start.set(Calendar.SECOND, 0)
                play()
            }.show()
        }.show()
    }

    /** Carries on from where you're watching when switching camera or jumping. */
    private fun keepPosition() {
        val pos = tile?.video?.positionMs ?: 0L
        start.add(Calendar.MILLISECOND, pos.toInt())
    }

    private fun shift(minutes: Int) {
        keepPosition()
        start.add(Calendar.MINUTE, minutes)
        play()
    }

    private fun togglePause() {
        val v = tile?.video ?: return
        val pause = !v.paused
        v.paused = pause
        pauseButton.text = if (pause) "Play" else "Pause"
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        keepPosition()
        tile?.stop()
        tile = null
    }

    override fun onRestart() {
        super.onRestart()
        play()
    }
}
