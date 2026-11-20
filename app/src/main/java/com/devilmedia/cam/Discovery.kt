package com.devilmedia.cam

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A recorder or camera found on the home network. */
data class Found(val ip: String, val description: String, val httpPort: Int, val activated: Boolean?)

/**
 * Finds Hikvision recorders on the Wi-Fi. First it asks the way Hikvision's own SADP tool does
 * (a short message on the local network that Hikvision devices answer), then, if nothing
 * answers, it checks the network for devices with Hikvision's usual ports open.
 */
object Discovery {

    fun find(context: Context): List<Found> {
        val results = ConcurrentHashMap<String, Found>()
        sadp(context).forEach { results[it.ip] = it }
        if (results.isEmpty()) portScan(context).forEach { results.putIfAbsent(it.ip, it) }
        return results.values.sortedBy { ipNumber(it.ip) }
    }

    private fun sadp(context: Context): List<Found> {
        val found = mutableListOf<Found>()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("devil-cam-find")?.apply { setReferenceCounted(false); acquire() }
        try {
            MulticastSocket(SADP_PORT).use { sock ->
                val group = InetAddress.getByName("239.255.255.250")
                try {
                    @Suppress("DEPRECATION")
                    sock.joinGroup(group)
                } catch (_: Exception) {
                }
                sock.soTimeout = 700
                val probe = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><Probe><Uuid>" +
                    UUID.randomUUID().toString().uppercase() + "</Uuid><Types>inquiry</Types></Probe>").toByteArray()
                val buf = ByteArray(8192)
                repeat(3) {
                    sock.send(DatagramPacket(probe, probe.size, group, SADP_PORT))
                    val until = System.currentTimeMillis() + 1200
                    while (System.currentTimeMillis() < until) {
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            sock.receive(p)
                            val xml = String(p.data, 0, p.length, Charsets.UTF_8)
                            if (!xml.contains("<ProbeMatch")) continue
                            val ip = Isapi.tag(xml, "IPv4Address") ?: p.address.hostAddress ?: continue
                            if (found.any { it.ip == ip }) continue
                            val desc = Isapi.tag(xml, "DeviceDescription") ?: Isapi.tag(xml, "DeviceType") ?: "Hikvision device"
                            val port = Isapi.tag(xml, "HttpPort")?.toIntOrNull() ?: 80
                            val activated = Isapi.tag(xml, "Activated")?.let { it.equals("true", true) }
                            found += Found(ip, desc, port, activated)
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                lock?.release()
            } catch (_: Exception) {
            }
        }
        return found
    }

    private fun portScan(context: Context): List<Found> {
        val (base, own) = subnet(context) ?: return emptyList()
        val found = ConcurrentHashMap<String, Found>()
        val pool = Executors.newFixedThreadPool(48)
        for (i in 1..254) {
            val ip = "$base.$i"
            if (ip == own) continue
            pool.execute {
                // 8000 is Hikvision's own port; 554 is the video (RTSP) port.
                if (open(ip, 8000) || (open(ip, 554) && open(ip, 80))) {
                    found[ip] = Found(ip, "Possible recorder or camera", 80, null)
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS)
        return found.values.toList()
    }

    private fun open(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 350); true }
    } catch (_: Exception) {
        false
    }

    /** The first three parts of this device's home network address, and its own address. */
    private fun subnet(context: Context): Pair<String, String>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp: LinkProperties = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val addr = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: return null
        val own = addr.hostAddress ?: return null
        return own.substringBeforeLast('.') to own
    }

    private fun ipNumber(ip: String): Long =
        ip.split('.').fold(0L) { acc, part -> acc * 256 + (part.toLongOrNull() ?: 0) }

    private const val SADP_PORT = 37020
}
