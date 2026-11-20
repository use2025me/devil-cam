package com.devilmedia.cam

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Devil Media Group colours. */
object Brand {
    const val BG = 0xFF1B1C1F.toInt()
    const val CARD = 0xFF26282D.toInt()
    const val CARD_PRESSED = 0xFF33363C.toInt()
    const val RED = 0xFFC8102E.toInt()
    const val RED_PRESSED = 0xFFA50D26.toInt()
    const val GREEN = 0xFF3FB984.toInt()
    const val AMBER = 0xFFE0A33A.toInt()
    const val SOFT_RED = 0xFFD9645A.toInt()
    const val GREY = 0xFF6E747C.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val MUTED = 0xFFAEB4BC.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.rounded(color: Int, radius: Int = 14, strokeColor: Int = 0, strokeWidth: Int = 3): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (strokeColor != 0) setStroke(dp(strokeWidth), strokeColor)
    }

/** Background that shows a clear white outline when selected with a TV remote. */
fun Context.focusBg(fill: Int, pressed: Int, radius: Int = 14): StateListDrawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, radius))
    addState(intArrayOf(android.R.attr.state_focused), rounded(fill, radius, strokeColor = Color.WHITE))
    addState(intArrayOf(), rounded(fill, radius))
}

private val mainHandler = Handler(Looper.getMainLooper())

/** Runs [work] in the background, then [done] on screen (unless the screen has closed). */
fun <T> Activity.background(work: () -> T, done: (T) -> Unit) {
    thread(isDaemon = true) {
        val result = work()
        mainHandler.post { if (!isFinishing && !isDestroyed) done(result) }
    }
}

/** Shared layout helpers. Screens are built in code so they work the same on phones and TVs. */
abstract class CamScreen : Activity() {
    protected lateinit var body: LinearLayout

    protected fun setupScreen(title: String?) {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Brand.BG)
        scroll.isFillViewport = true
        body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        val w = resources.displayMetrics.widthPixels
        val maxW = dp(680)
        val side = if (w > maxW + dp(48)) (w - maxW) / 2 else dp(20)
        body.setPadding(side, dp(28), side, dp(36))
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        if (title != null) heading(title)
    }

    protected fun heading(t: String, parent: LinearLayout = body) {
        text(t, 26f, Brand.TEXT, bold = true, parent = parent)
        gap(4, parent)
    }

    protected fun text(
        t: CharSequence,
        size: Float = 16f,
        color: Int = Brand.MUTED,
        bold: Boolean = false,
        center: Boolean = false,
        parent: LinearLayout = body
    ): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        v.setLineSpacing(0f, 1.15f)
        if (bold) v.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (center) v.gravity = Gravity.CENTER_HORIZONTAL
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(6)
        parent.addView(v, lp)
        return v
    }

    protected fun gap(h: Int, parent: LinearLayout = body) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))
    }

    protected fun button(
        label: String,
        primary: Boolean = false,
        parent: LinearLayout = body,
        onClick: () -> Unit
    ): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 17f
        b.setTextColor(Color.WHITE)
        b.background = if (primary) focusBg(Brand.RED, Brand.RED_PRESSED) else focusBg(Brand.CARD, Brand.CARD_PRESSED)
        b.stateListAnimator = null
        b.minHeight = dp(54)
        b.setPadding(dp(18), dp(10), dp(18), dp(10))
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        lp.bottomMargin = dp(6)
        parent.addView(b, lp)
        return b
    }

    /** A rounded panel. If [onClick] is given it can be selected with a remote. */
    protected fun card(parent: LinearLayout = body, onClick: (() -> Unit)? = null): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(10))
        if (onClick != null) {
            c.background = focusBg(Brand.CARD, Brand.CARD_PRESSED)
            c.isFocusable = true
            c.isClickable = true
            c.setOnClickListener { onClick() }
        } else {
            c.background = rounded(Brand.CARD)
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        lp.bottomMargin = dp(6)
        parent.addView(c, lp)
        return c
    }

    protected fun dialog(): AlertDialog.Builder = AlertDialog.Builder(this, R.style.Theme_DevilCam_Dialog)

    protected fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
