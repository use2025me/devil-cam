package com.devilmedia.cam

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One camera channel on the recorder (Hikvision numbers them 1, 2, 3...). */
data class Channel(val number: Int, val name: String)

/** The saved recorder: where it is, how to log in, and its cameras. */
data class Recorder(
    val host: String,
    val httpPort: Int,
    val rtspPort: Int,
    val user: String,
    val password: String,
    val model: String,
    val channels: List<Channel>
) {
    private fun auth(): String = Uri.encode(user) + ":" + Uri.encode(password) + "@"

    /** Live video. The sub-stream (lighter) is used in the grid, the main stream full screen. */
    fun liveUrl(channel: Int, sub: Boolean): String =
        "rtsp://${auth()}$host:$rtspPort/Streaming/Channels/${channel}0${if (sub) 2 else 1}"

    /** Recorded video from the hard drive, starting at [startUtc] (format yyyyMMdd'T'HHmmss'Z'). */
    fun playbackUrl(channel: Int, startUtc: String): String =
        "rtsp://${auth()}$host:$rtspPort/Streaming/tracks/${channel}01?starttime=$startUtc"

    fun baseHttp(): String = if (httpPort == 80) "http://$host" else "http://$host:$httpPort"
}

/** Keeps the recorder details on this device. The password is encrypted with a key that never leaves it. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("devil_cam", Context.MODE_PRIVATE)

    var recorder: Recorder?
        get() {
            val host = prefs.getString("host", null) ?: return null
            val pw = decrypt(prefs.getString("pw", null) ?: return null) ?: return null
            val chans = (prefs.getString("channels", "") ?: "").split("\n").filter { it.contains("|") }.map {
                val (n, name) = it.split("|", limit = 2)
                Channel(n.toInt(), name)
            }
            return Recorder(
                host = host,
                httpPort = prefs.getInt("httpPort", 80),
                rtspPort = prefs.getInt("rtspPort", 554),
                user = prefs.getString("user", "admin") ?: "admin",
                password = pw,
                model = prefs.getString("model", "") ?: "",
                channels = chans.ifEmpty { (1..4).map { Channel(it, "Camera $it") } }
            )
        }
        set(r) {
            if (r == null) {
                prefs.edit().clear().apply()
                return
            }
            prefs.edit()
                .putString("host", r.host)
                .putInt("httpPort", r.httpPort)
                .putInt("rtspPort", r.rtspPort)
                .putString("user", r.user)
                .putString("pw", encrypt(r.password))
                .putString("model", r.model)
                .putString("channels", r.channels.joinToString("\n") { "${it.number}|${it.name.replace("\n", " ")}" })
                .apply()
        }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String? = try {
        val all = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val ALIAS = "devil_cam_login"
    }
}
