package com.devilmedia.cam

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Anything Devil Cam can show: a camera on the recorder, a doorbell, or a separate camera. */
interface CamSource {
    /** Stays the same between app starts, used to open the right camera. */
    val key: String
    val name: String
    val doorbell: Boolean
    val user: String
    val password: String
    fun liveUrl(sub: Boolean): String
    /** A still photo as JPEG, or null if this device can't give one. */
    fun snapshot(): ByteArray?
    /** Recorded video from [startUtc] (yyyyMMdd'T'HHmmss'Z'), or null if it can't play back. */
    fun playbackUrl(startUtc: String): String?
}

/** One camera on the Hikvision recorder. */
class RecorderCam(val rec: Recorder, val ch: Channel) : CamSource {
    override val key = "rec:${ch.number}"
    override val name = ch.name
    override val doorbell = false
    override val user = rec.user
    override val password = rec.password
    override fun liveUrl(sub: Boolean) = rec.liveUrl(ch.number, sub)
    override fun snapshot() = Isapi(rec.baseHttp(), rec.user, rec.password).snapshot(ch.number)
    override fun playbackUrl(startUtc: String) = rec.playbackUrl(ch.number, startUtc)
}

/** Makes of doorbells and cameras that can be added on their own. */
enum class Make(val label: String, val hint: String) {
    HIKVISION("Hikvision", "Hikvision doorbells and cameras. Use the device's own username and password."),
    EZVIZ("EZVIZ", "Username is admin. The password is the 6-letter verification code on the device's sticker. Turn on \"Local video\" / RTSP in the EZVIZ app first."),
    REOLINK("Reolink", "Use the username and password you set in the Reolink app (usually admin)."),
    OTHER("Other (RTSP link)", "Any camera with an RTSP link, e.g. rtsp://192.168.1.50:554/stream1")
}

/** A doorbell or camera added on its own (not through the recorder). Recordings stay on the device itself: no monthly fees. */
data class Device(
    val id: String,
    val brand: Make,
    override val name: String,
    val host: String,
    val httpPort: Int,
    override val user: String,
    override val password: String,
    override val doorbell: Boolean,
    /** Only for [Make.OTHER]: the full RTSP link. */
    val rtsp: String = ""
) : CamSource {
    override val key: String get() = "dev:$id"

    private fun http(): String = if (httpPort == 80) "http://$host" else "http://$host:$httpPort"

    override fun liveUrl(sub: Boolean): String = when (brand) {
        Make.HIKVISION -> "rtsp://$host:554/Streaming/Channels/10${if (sub) 2 else 1}"
        Make.EZVIZ -> if (sub) "rtsp://$host:554/ch1/sub/av_stream" else "rtsp://$host:554/H.264"
        Make.REOLINK -> "rtsp://$host:554/Preview_01_${if (sub) "sub" else "main"}"
        Make.OTHER -> rtsp
    }

    override fun snapshot(): ByteArray? = try {
        when (brand) {
            Make.HIKVISION -> Isapi(http(), user, password).snapshot(1)
            Make.REOLINK -> {
                val u = "${http()}/cgi-bin/api.cgi?cmd=Snap&channel=0&rs=devilcam" +
                    "&user=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(password, "UTF-8")}"
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 8000
                try {
                    if (c.responseCode == 200 && c.contentType?.startsWith("image") == true) c.inputStream.use { it.readBytes() } else null
                } finally {
                    c.disconnect()
                }
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** Hikvision devices play back what's on their memory card the same way the recorder does. */
    override fun playbackUrl(startUtc: String): String? = when (brand) {
        Make.HIKVISION -> "rtsp://$host:554/Streaming/tracks/101?starttime=$startUtc"
        else -> null
    }

    /** Hikvision devices can send motion and doorbell-press events. */
    val canAlert: Boolean get() = brand == Make.HIKVISION

    fun isapi(): Isapi = Isapi(http(), user, password)
}

/** Everything to show, in order: recorder cameras first, then added doorbells and cameras. */
fun allSources(store: Store, hiddenRecorderChannels: Set<Int> = emptySet()): List<CamSource> {
    val rec = store.recorder
    val cams = rec?.channels?.map { RecorderCam(rec, it) } ?: emptyList()
    val visible = cams.filter { it.ch.number !in hiddenRecorderChannels }.ifEmpty { cams }
    return visible + store.devices
}
