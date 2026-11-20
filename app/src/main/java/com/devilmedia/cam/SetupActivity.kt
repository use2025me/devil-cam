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
        setupScreen("Devil Cam")
        text("Watch your home cameras on this phone, tablet or TV.")
        gap(10)
        text("Make sure this device is on the same Wi-Fi as your recorder, then tap below.")
        gap(6)
        val find = button("Find my recorder", primary = true) { search() }
        button("Type the address myself") { askAddress() }
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
                text(f.description, 17f, Brand.TEXT, bold = true, parent = c)
                text(
                    f.ip + if (f.activated == false) "  ·  not set up yet. Set it up with Hik-Connect first." else "",
                    14f, parent = c
                )
            }
            list.getChildAt(0)?.requestFocus()
        }
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
