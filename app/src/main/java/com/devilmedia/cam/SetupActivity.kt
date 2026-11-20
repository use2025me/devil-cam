package com.devilmedia.cam

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** First run: find the recorder on the Wi-Fi and log in to it once. */
class SetupActivity : CamScreen() {

    private lateinit var status: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(null)
        gap(20)
        body.addView(android.widget.FrameLayout(this).apply {
            background = rounded(0x33C8102E, 24)
            addView(icon(R.drawable.ic_videocam, 40, 0xFFFF4D5E.toInt()),
                android.widget.FrameLayout.LayoutParams(dp(40), dp(40), android.view.Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(80), dp(80)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
        gap(16)
        text("Devil Cam", 28f, Brand.TEXT, bold = true, center = true)
        text("Your home cameras on your phone, tablet and TV.", 15f, center = true)
        gap(18)
        text("Make sure this device is on the same Wi-Fi as your recorder.", 13f, Brand.GREY, center = true)
        gap(8)
        val find = button("Find my recorder", primary = true) { search() }.withIcon(R.drawable.ic_search)
        button("Type the address myself") { askAddress() }.withIcon(R.drawable.ic_edit)
        gap(12)
        status = text("", 15f)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        gap(18)
        text("Works with Hikvision recorders and cameras (including Hik-Connect ones). More brands are coming.", 13f, Brand.GREY)
        find.requestFocus()
    }

    private fun search() {
        list.removeAllViews()
        status.text = "Looking for recorders on your Wi-Fi… this takes a few seconds."
        background({ Discovery.find(this) }) { found ->
            if (found.isEmpty()) {
                status.text = "Nothing found. Check this device is on your home Wi-Fi, or tap \"Type the address myself\". " +
                    "The address is in Hik-Connect, or on the recorder: Menu → Configuration → Network."
                return@background
            }
            status.text = "Found ${found.size}. Tap your recorder:"
            for (f in found) {
                val c = card(list) { askLogin(f.ip, f.httpPort) }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(android.widget.FrameLayout(this).apply {
                    background = rounded(0x33C8102E, 12)
                    addView(icon(R.drawable.ic_videocam, 22, 0xFFFF4D5E.toInt()),
                        android.widget.FrameLayout.LayoutParams(dp(22), dp(22), android.view.Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                text(f.description, 17f, Brand.TEXT, bold = true, parent = col)
                text(
                    f.ip + if (f.activated == false) "  ·  not set up yet. Set it up with Hik-Connect first." else "",
                    13f, parent = col
                )
                row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(icon(R.drawable.ic_next, 22, Brand.GREY))
                c.addView(row)
            }
            list.getChildAt(0)?.requestFocus()
        }
    }

    private fun android.widget.Button.withIcon(res: Int): android.widget.Button = apply {
        val d = context.getDrawable(res)!!.mutate()
        d.setTint(Color.WHITE)
        d.setBounds(0, 0, dp(20), dp(20))
        setCompoundDrawablesRelative(d, null, null, null)
        compoundDrawablePadding = dp(10)
        gravity = android.view.Gravity.CENTER
    }

    private fun field(hint: String, value: String, password: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(Brand.GREY)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun askAddress() {
        val input = field("e.g. 192.168.1.64", "", false)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        dialog().setTitle("Recorder address")
            .setMessage("You'll find it in Hik-Connect, or on the recorder under Menu → Configuration → Network.")
            .setView(box)
            .setPositiveButton("Next") { _, _ ->
                val raw = input.text.toString().trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
                if (raw.isEmpty()) return@setPositiveButton
                val host = raw.substringBefore(':')
                val port = raw.substringAfter(':', "80").toIntOrNull() ?: 80
                askLogin(host, port)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askLogin(host: String, httpPort: Int) {
        val user = field("Username", "admin", false)
        val pw = field("Password", "", true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(user)
            addView(pw)
        }
        dialog().setTitle("Log in to your recorder")
            .setMessage("Use the same username and password as the recorder itself. They're stored encrypted on this device only.")
            .setView(box)
            .setPositiveButton("Connect") { _, _ ->
                connect(host, httpPort, user.text.toString().trim(), pw.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
        pw.requestFocus()
    }

    private fun connect(host: String, httpPort: Int, user: String, pw: String) {
        status.text = "Connecting to $host…"
        list.removeAllViews()
        background({
            try {
                val api = Isapi(if (httpPort == 80) "http://$host" else "http://$host:$httpPort", user, pw)
                val info = api.deviceInfo()
                val channels = api.channels()
                val rec = Recorder(host, httpPort, api.rtspPort() ?: 554, user, pw, info.model, channels)
                Result.success(Triple(rec, info, api.upnpEnabled()))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }) { result ->
            result.onSuccess { (rec, info, upnp) ->
                Store(this).recorder = rec
                startActivity(
                    Intent(this, SafetyActivity::class.java)
                        .putExtra("firstRun", true)
                        .putExtra("firmware", info.firmware)
                        .putExtra("upnp", when (upnp) { true -> 1; false -> 0; null -> -1 })
                )
                finish()
            }.onFailure { e ->
                status.text = (e as? LoginProblem)?.message ?: "Couldn't connect: ${e.message ?: "unknown problem"}"
            }
        }
    }
}
