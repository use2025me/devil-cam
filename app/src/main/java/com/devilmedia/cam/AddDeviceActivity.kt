package com.devilmedia.cam

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID

/** Add a doorbell or camera on its own: Hikvision, EZVIZ, Reolink, or any camera with an RTSP link. */
class AddDeviceActivity : CamScreen() {

    private var brand = Make.HIKVISION
    private lateinit var form: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen("Add a doorbell or camera")
        text("Doorbells and cameras that record to their own memory card work with Devil Cam for free, with no monthly fees.")
        gap(10)
        text("Which make is it?", 15f, Brand.TEXT, bold = true)
        for (b in Make.values()) {
            val c = card { pick(b) }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(FrameLayout(this).apply {
                background = rounded(0x33C8102E, 12)
                addView(icon(R.drawable.ic_videocam, 20, 0xFFFF4D5E.toInt()), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
            row.addView(TextView(this).apply { text = b.label; setTextColor(Color.WHITE); textSize = 17f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(icon(R.drawable.ic_next, 22, Brand.GREY))
            c.addView(row)
            if (b == Make.HIKVISION) c.requestFocus()
        }
        form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(form)
        status = text("", 14f)
    }

    private fun field(hint: String, value: String = "", password: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(Brand.GREY)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun pick(b: Make) {
        brand = b
        form.removeAllViews()
        status.text = ""
        gap(16, form)
        heading(b.label, form)
        text(b.hint, 14f, parent = form)
        val name = field("Name, e.g. Front door", "Front door")
        val doorbell = CheckBox(this).apply {
            text = "This is a doorbell"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        val host = field("Address, e.g. 192.168.1.50")
        val user = field("Username", "admin")
        val pw = field(if (b == Make.EZVIZ) "Verification code (from the sticker)" else "Password", password = true)
        val rtsp = field("RTSP link, e.g. rtsp://192.168.1.50:554/stream1")
        form.addView(name)
        form.addView(doorbell)
        if (b == Make.OTHER) form.addView(rtsp) else {
            form.addView(host)
            button("Find it on my Wi-Fi") { find(host) }
        }
        form.addView(user)
        form.addView(pw)
        button("Add", primary = true, parent = form) {
            val h = host.text.toString().trim().removePrefix("http://").trimEnd('/')
            val link = rtsp.text.toString().trim()
            if (b == Make.OTHER && !link.startsWith("rtsp://")) { status.text = "Enter the camera's RTSP link (it starts with rtsp://)."; return@button }
            if (b != Make.OTHER && h.isEmpty()) { status.text = "Enter the address, or tap \"Find it on my Wi-Fi\"."; return@button }
            val d = Device(
                id = UUID.randomUUID().toString().take(8),
                brand = b,
                name = name.text.toString().trim().ifEmpty { if (doorbell.isChecked) "Doorbell" else "Camera" },
                host = if (b == Make.OTHER) (android.net.Uri.parse(link).host ?: "") else h.substringBefore(':'),
                httpPort = if (b == Make.OTHER) 80 else (h.substringAfter(':', "80").toIntOrNull() ?: 80),
                user = user.text.toString().trim(),
                password = pw.text.toString(),
                doorbell = doorbell.isChecked,
                rtsp = link
            )
            test(d)
        }
    }

    // "Find it on my Wi-Fi" sits under the address box.
    private fun button(label: String, onClick: () -> Unit) = button(label, false, form, onClick)

    private fun find(host: EditText) {
        status.text = "Looking on your Wi-Fi…"
        background({ Discovery.find(this) }) { found ->
            if (found.isEmpty()) {
                status.text = "Nothing found. Type the address instead. You'll find it in the maker's app or your router."
                return@background
            }
            status.text = "Pick it from the list."
            dialog().setTitle("Found on your Wi-Fi")
                .setItems(found.map { "${it.description} · ${it.ip}" }.toTypedArray()) { _, i ->
                    host.setText(found[i].ip)
                    status.text = ""
                }
                .show()
        }
    }

    /** Checks the login where we can, then saves. */
    private fun test(d: Device) {
        status.text = "Checking ${d.name}…"
        background({
            when (d.brand) {
                Make.HIKVISION -> try { d.isapi().deviceInfo(); null } catch (e: Exception) { e.message ?: "Couldn't connect." }
                Make.REOLINK -> if (d.snapshot() != null) null else "Couldn't log in. Check the address, username and password."
                else -> null // Checked when the video starts.
            }
        }) { problem ->
            if (problem != null) {
                status.text = problem
                return@background
            }
            val store = Store(this)
            store.devices = store.devices + d
            toast("${d.name} added")
            startActivity(Intent(this, GridActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }
}
