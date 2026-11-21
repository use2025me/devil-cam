package com.devilmedia.cam

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/** What came back from the recorder. */
class Reply(val code: Int, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
    val ok: Boolean get() = code in 200..299
}

/**
 * Talks to a Hikvision recorder over its local web interface (ISAPI), using the same
 * "digest" login the recorder's own web page uses, so the password is never sent in plain text.
 */
class Isapi(private val base: String, private val user: String, private val password: String) {

    fun get(path: String): Reply = request(path, null)

    private fun request(path: String, authHeader: String?): Reply {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 8000
        c.instanceFollowRedirects = false
        if (authHeader != null) c.setRequestProperty("Authorization", authHeader)
        try {
            val code = c.responseCode
            if (code == 401 && authHeader == null) {
                val challenge = c.getHeaderFields().entries
                    .filter { it.key.equals("WWW-Authenticate", ignoreCase = true) }
                    .flatMap { it.value }
                val digest = challenge.firstOrNull { it.trim().startsWith("Digest", ignoreCase = true) }
                val header = when {
                    digest != null -> digestHeader(digest, path)
                    challenge.any { it.trim().startsWith("Basic", ignoreCase = true) } ->
                        "Basic " + android.util.Base64.encodeToString(
                            "$user:$password".toByteArray(), android.util.Base64.NO_WRAP
                        )
                    else -> return Reply(401, ByteArray(0))
                }
                c.disconnect()
                return request(path, header)
            }
            val stream = if (code < 400) c.inputStream else c.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Reply(code, body)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Opens a long-running connection (used for the recorder's live event feed).
     * Handles the login, then returns the open connection for reading.
     */
    fun openStream(path: String): HttpURLConnection {
        fun open(auth: String?): HttpURLConnection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 0
            if (auth != null) setRequestProperty("Authorization", auth)
        }
        val first = open(null)
        if (first.responseCode != 401) return first
        val challenge = first.headerFields.entries
            .filter { it.key.equals("WWW-Authenticate", ignoreCase = true) }
            .flatMap { it.value }
        first.disconnect()
        val digest = challenge.firstOrNull { it.trim().startsWith("Digest", ignoreCase = true) }
        val header = if (digest != null) digestHeader(digest, path)
        else "Basic " + android.util.Base64.encodeToString("$user:$password".toByteArray(), android.util.Base64.NO_WRAP)
        val c = open(header)
        if (c.responseCode !in 200..299) {
            val code = c.responseCode
            c.disconnect()
            throw IOException("event feed refused (code $code)")
        }
        return c
    }

    private fun digestHeader(challenge: String, uri: String): String {
        val params = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]+))""").findAll(challenge.substringAfter(' '))
            .associate { m -> m.groupValues[1].lowercase() to (m.groupValues[2].ifEmpty { m.groupValues[3] }) }
        val realm = params["realm"] ?: ""
        val nonce = params["nonce"] ?: ""
        val opaque = params["opaque"]
        val qop = params["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        val cnonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val nc = "00000001"
        val ha1 = md5("$user:$realm:$password")
        val ha2 = md5("GET:$uri")
        val response = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"")
        if (qop != null) sb.append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
        if (opaque != null) sb.append(", opaque=\"$opaque\"")
        params["algorithm"]?.let { sb.append(", algorithm=$it") }
        return sb.toString()
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ---- Recorder questions ----

    class DeviceInfo(val model: String, val firmware: String, val name: String)

    /** Checks the login and reads the model. Throws a friendly message if it can't. */
    fun deviceInfo(): DeviceInfo {
        val r = try {
            get("/ISAPI/System/deviceInfo")
        } catch (e: IOException) {
            throw LoginProblem("Couldn't reach the recorder at this address. Check you're on the same Wi-Fi.")
        }
        when {
            r.code == 401 -> throw LoginProblem("The username or password wasn't accepted.")
            r.code == 403 -> throw LoginProblem("The recorder refused the login. It may be locked for a while after too many tries.")
            !r.ok -> throw LoginProblem("The recorder answered, but not as expected (code ${r.code}).")
        }
        val t = r.text
        return DeviceInfo(
            model = tag(t, "model") ?: "Hikvision recorder",
            firmware = listOfNotNull(tag(t, "firmwareVersion"), tag(t, "firmwareReleasedDate")).joinToString(" "),
            name = tag(t, "deviceName") ?: ""
        )
    }

    /** The recorder's cameras, with the names set on the recorder. Falls back to 4 if it won't say. */
    fun channels(): List<Channel> {
        val found = linkedMapOf<Int, String>()
        try {
            val r = get("/ISAPI/Streaming/channels")
            if (r.ok) {
                Regex("""<StreamingChannel\b.*?</StreamingChannel>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(r.text).forEach { m ->
                        val id = tag(m.value, "id")?.toIntOrNull() ?: return@forEach
                        if (id % 100 != 1) return@forEach
                        val enabled = tag(m.value, "enabled")
                        if (enabled == "false") return@forEach
                        val ch = id / 100
                        found[ch] = tag(m.value, "channelName")?.takeIf { it.isNotBlank() } ?: "Camera $ch"
                    }
            }
        } catch (_: Exception) {
        }
        if (found.isEmpty()) return (1..4).map { Channel(it, "Camera $it") }
        // Use the names set for the wired cameras, if there are any.
        try {
            val r = get("/ISAPI/System/Video/inputs/channels")
            if (r.ok) {
                Regex("""<VideoInputChannel\b.*?</VideoInputChannel>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(r.text).forEach { m ->
                        val id = tag(m.value, "id")?.toIntOrNull() ?: return@forEach
                        val name = tag(m.value, "name")
                        if (name != null && name.isNotBlank() && found.containsKey(id)) found[id] = name
                    }
            }
        } catch (_: Exception) {
        }
        return found.map { Channel(it.key, it.value) }
    }

    /**
     * Cameras the recorder currently gets no picture from ("NO VIDEO").
     * Empty if it won't say.
     */
    fun noSignalChannels(): Set<Int> = try {
        val r = get("/ISAPI/System/Video/inputs/channels")
        if (!r.ok) emptySet() else
            Regex("""<VideoInputChannel\b.*?</VideoInputChannel>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(r.text)
                .filter { tag(it.value, "resDesc")?.contains("NO VIDEO", ignoreCase = true) == true }
                .mapNotNull { tag(it.value, "id")?.toIntOrNull() }
                .toSet()
    } catch (_: Exception) {
        emptySet()
    }

    /** True / false if the recorder says whether UPnP is on, or null if it won't say. */
    fun upnpEnabled(): Boolean? = try {
        val r = get("/ISAPI/System/Network/UPnP")
        if (r.ok) tag(r.text, "enabled")?.toBooleanStrictOrNull() else null
    } catch (_: Exception) {
        null
    }

    /** The recorder's video (RTSP) port, if it says. Usually 554. */
    fun rtspPort(): Int? = try {
        val r = get("/ISAPI/Security/adminAccesses")
        if (!r.ok) null else
            Regex("""<AdminAccessProtocol\b.*?</AdminAccessProtocol>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(r.text)
                .firstOrNull { tag(it.value, "protocol").equals("RTSP", ignoreCase = true) }
                ?.let { tag(it.value, "portNo")?.toIntOrNull() }
    } catch (_: Exception) {
        null
    }

    /** A still photo from a camera, as JPEG bytes, or null. */
    fun snapshot(channel: Int): ByteArray? = try {
        val r = get("/ISAPI/Streaming/channels/${channel}01/picture")
        if (r.ok && r.body.size > 100) r.body else null
    } catch (_: Exception) {
        null
    }

    companion object {
        fun tag(xml: String, name: String): String? =
            Regex("""<$name(?:\s[^>]*)?>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
    }
}

class LoginProblem(message: String) : Exception(message)
