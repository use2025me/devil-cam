package com.devilmedia.cam

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Watch back what the recorder saved on its hard drive. */
class PlaybackActivity : Activity() {

    private lateinit var sources: List<CamSource>
    private var camIndex = 0
    private val start: Calendar = Calendar.getInstance().apply {
        add(Calendar.HOUR_OF_DAY, -1)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    private lateinit var videoBox: FrameLayout
    private lateinit var info: TextView
    private lateinit var camButton: TextView
    private lateinit var dayButton: TextView
    private lateinit var timeButton: TextView
    private lateinit var pauseButton: FrameLayout
    private lateinit var pauseIcon: android.widget.ImageView
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
        sources = allSources(Store(this)).filter { it.playbackUrl("20000101T000000Z") != null }
        if (sources.isEmpty()) {
            android.widget.Toast.makeText(this, "None of your cameras can play back recordings here yet.", android.widget.Toast.LENGTH_LONG).show()
            return finish()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Brand.BG_DEEP)
        }

        // Header: back, title and what's playing.
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(16), dp(10))
        }
        head.addView(roundButton(R.drawable.ic_back, 40, Brand.CARD) { finish() })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(TextView(this).apply {
            text = "Playback"
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        info = TextView(this).apply {
            setTextColor(Brand.MUTED)
            textSize = 12f
            maxLines = 1
        }
        titles.addView(info)
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(pill("REC", 0x33C8102E, 0xFFFF6B7D.toInt()))
        root.addView(head)

        videoBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(videoBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Choices: which camera, which day, what time.
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(4))
        }
        camButton = chip(R.drawable.ic_videocam) { pickCamera() }
        dayButton = chip(R.drawable.ic_calendar) { pickDay() }
        timeButton = chip(R.drawable.ic_clock) { pickTime() }
        chips.addView(camButton); chips.addView(dayButton); chips.addView(timeButton)
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })

        // Controls: back 5 minutes, pause/play, forward 5 minutes.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(18))
        }
        controls.addView(labelled(roundButton(R.drawable.ic_rewind, 50, Brand.CARD) { shift(-5) }, "−5 min"))
        pauseIcon = android.widget.ImageView(this)
        pauseButton = roundButton(R.drawable.ic_pause, 64, Brand.RED) { togglePause() }
        controls.addView(labelled(pauseButton, ""))
        controls.addView(labelled(roundButton(R.drawable.ic_forward, 50, Brand.CARD) { shift(5) }, "+5 min"))
        root.addView(controls)

        setContentView(root)
        play()
        camButton.requestFocus()
    }

    private fun labelled(v: View, label: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), 0, dp(16), 0)
        addView(v)
        addView(TextView(context).apply {
            text = label
            setTextColor(Brand.MUTED)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun chip(iconRes: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        background = focusBg(Brand.CARD, Brand.CARD_PRESSED, radius = 22)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        setPadding(dp(14), dp(10), dp(16), dp(10))
        val d = context.getDrawable(iconRes)!!.mutate()
        d.setTint(0xFFFF6B7D.toInt())
        d.setBounds(0, 0, dp(18), dp(18))
        setCompoundDrawablesRelative(d, null, null, null)
        compoundDrawablePadding = dp(8)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        }
    }

    private fun play() {
        tile?.stop()
        videoBox.removeAllViews()
        val ch = sources[camIndex]
        val t = CameraTile(this, ch).apply { isFocusable = false; foreground = null }
        videoBox.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Hikvision recorders read this time as their own local time.
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).format(start.time)
        t.startRecording(ch.playbackUrl(stamp)!!)
        tile = t
        setPauseIcon(false)
        camButton.text = ch.name
        dayButton.text = dayLabel(start)
        timeButton.text = SimpleDateFormat("HH:mm", Locale.UK).format(start.time)
        updateInfo()
    }

    private fun updateInfo() {
        val pos = tile?.video?.positionMs ?: 0L
        val now = (start.clone() as Calendar).apply { add(Calendar.MILLISECOND, pos.toInt()) }
        info.text = "${sources[camIndex].name} · ${dayLabel(now)} at ${SimpleDateFormat("HH:mm:ss", Locale.UK).format(now.time)}"
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
            .setItems(sources.map { it.name }.toTypedArray()) { _, i -> camIndex = i; keepPosition(); play() }
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

    private fun setPauseIcon(paused: Boolean) {
        (pauseButton.getChildAt(0) as? android.widget.ImageView)
            ?.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
    }

    private fun togglePause() {
        val v = tile?.video ?: return
        val pause = !v.paused
        v.paused = pause
        setPauseIcon(pause)
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
