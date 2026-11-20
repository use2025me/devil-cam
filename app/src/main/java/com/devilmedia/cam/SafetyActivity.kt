package com.devilmedia.cam

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout

/** Plain-language safety check for the recorder. */
class SafetyActivity : CamScreen() {

    private lateinit var results: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rec = Store(this).recorder ?: return finish()
        val firstRun = intent.getBooleanExtra("firstRun", false)
        setupScreen(if (firstRun) "Connected!" else "Safety check")
        text(
            if (firstRun) "Devil Cam found ${rec.channels.size} cameras on your ${rec.model}. Here's a quick safety check first."
            else "A quick look at how safe your ${rec.model} is."
        )
        gap(8)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(results)
        gap(12)
        val go = button(if (firstRun) "Watch my cameras" else "Done", primary = true) {
            if (firstRun) startActivity(Intent(this, GridActivity::class.java))
            finish()
        }
        go.requestFocus()

        if (intent.hasExtra("upnp")) {
            val u = intent.getIntExtra("upnp", -1)
            show(rec, intent.getStringExtra("firmware") ?: "", if (u == -1) null else u == 1)
        } else {
            text("Checking…", parent = results)
            background({
                val api = Isapi(rec.baseHttp(), rec.user, rec.password)
                val fw = try { api.deviceInfo().firmware } catch (_: Exception) { "" }
                fw to api.upnpEnabled()
            }) { (fw, upnp) -> show(rec, fw, upnp) }
        }
    }

    private fun show(rec: Recorder, firmware: String, upnp: Boolean?) {
        results.removeAllViews()
        val findings = listOfNotNull(
            Safety.password(rec.user, rec.password),
            Safety.user(rec.user),
            Safety.upnp(upnp),
            Safety.firmware(rec.model, firmware),
            Safety.away()
        ).sortedBy { it.level.ordinal * -1 }
        for (f in findings) {
            val c = card(results)
            val (mark, colour) = when (f.level) {
                Finding.Level.GOOD -> "✓" to Brand.GREEN
                Finding.Level.WARN -> "!" to Brand.AMBER
                Finding.Level.BAD -> "✕" to Brand.SOFT_RED
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(android.widget.TextView(this).apply {
                text = mark
                setTextColor(colour)
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor((colour and 0x00FFFFFF) or 0x33000000)
                }
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(14); topMargin = dp(2) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text(f.title, 16f, Brand.TEXT, bold = true, parent = col)
            text(f.detail, 13f, parent = col)
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            c.addView(row)
        }
    }
}
