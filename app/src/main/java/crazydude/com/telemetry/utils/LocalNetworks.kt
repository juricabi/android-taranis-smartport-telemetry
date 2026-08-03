package crazydude.com.telemetry.utils

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The networks this phone is actually on, and a way to find a telemetry module
 * sitting on one of them.
 *
 * [WifiNetworkBinder] asks ConnectivityManager, which answers "which network is
 * this device a client of". That is the right question when the phone has
 * joined a transmitter's access point, and the wrong one when the phone is
 * *running* the access point: a hotspot is not a Network, so it is invisible
 * there, and mobile data is simultaneously the default route.
 *
 * Enumerating the interfaces directly sees both, which is what makes it
 * possible to say "the module is on my hotspot, on that subnet, go and look for
 * it there".
 */
object LocalNetworks {

    /**
     * How useful an interface is for telemetry, lowest first.
     *
     * Wi-Fi is where a module lives, whether the phone joined the module's
     * access point or the module joined the phone's hotspot. Mobile data never
     * carries telemetry and only gets in the way, so it sinks to the bottom.
     */
    private fun rank(name: String, hotspot: Boolean, loopback: Boolean): Int = when {
        name.startsWith("wlan") && !hotspot -> 0
        hotspot -> 1
        name.startsWith("rmnet") || name.startsWith("ccmni") ||
            name.startsWith("rmnet_data") -> 4
        // this device: real, occasionally the right answer, but not what
        // anyone is looking for first
        loopback -> 3
        else -> 2
    }

    /** One usable IPv4 interface. */
    class Iface(
        val name: String,
        val address: String,
        val prefix: Int,
        val likelyHotspot: Boolean,
        val loopback: Boolean,
        val rank: Int
    ) {
        /** What the spinner shows. */
        fun label(): String {
            val kind = when {
                loopback -> "this device"
                likelyHotspot -> "hotspot"
                name.startsWith("wlan") -> "Wi-Fi"
                name.startsWith("rmnet") || name.startsWith("ccmni") -> "mobile"
                else -> name
            }
            return "$kind — $address"
        }

        /** The /24 this address sits in, as a prefix like "192.168.43." */
        fun subnet24(): String {
            val dot = address.lastIndexOf('.')
            return if (dot > 0) address.substring(0, dot + 1) else ""
        }
    }

    /**
     * Every interface that is up, not loopback and has an IPv4 address.
     *
     * The hotspot guess is only for labelling. Vendors name the interface
     * anything from ap0 to swlan0 to wlan1, so the name is a hint and the
     * address range is the better clue — Android hands out 192.168.43.x by
     * default, and the phone itself takes the .1. Nothing depends on the guess
     * being right: the user picks from the list either way.
     */
    fun list(): List<Iface> {
        val out = ArrayList<Iface>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (iface in interfaces) {
                if (!iface.isUp) continue
                // Loopback is kept rather than skipped: a telemetry source can
                // run on the phone itself — a MAVLink router, a serial bridge —
                // or be forwarded to it over USB with `adb reverse`, and then
                // this device is genuinely the network to use.
                val loopback = iface.isLoopback
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address
                    if (addr !is Inet4Address) continue
                    val host = addr.hostAddress ?: continue
                    val name = iface.name ?: ""
                    val hotspot = !loopback && (name.startsWith("ap") ||
                        name.startsWith("swlan") || name == "wlan1" ||
                        host.startsWith("192.168.43."))
                    out.add(
                        Iface(
                            name, host, ia.networkPrefixLength.toInt(), hotspot,
                            loopback, rank(name, hotspot, loopback)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // an interface list is a nicety; never let it stop a connection
        }
        // Wi-Fi at the top, mobile at the bottom, so the useful one is the one
        // already in view.
        out.sortWith(Comparator { a, b ->
            if (a.rank != b.rank) a.rank - b.rank else a.name.compareTo(b.name)
        })
        return out
    }

    /**
     * Look for something answering on [port] across the interface's /24.
     *
     * This is the honest way to find a module that joined the phone's hotspot:
     * the phone is the gateway there, so the gateway address is the phone
     * itself and tells you nothing. Asking every address on the subnet whether
     * it is serving telemetry answers the question directly.
     *
     * Runs its own threads and calls [onResult] on the caller's thread, so the
     * caller must not be the main thread.
     */
    fun scan(
        iface: Iface,
        port: Int,
        timeoutMs: Int,
        onProgress: (Int, Int) -> Unit,
        onResult: (List<String>) -> Unit
    ) {
        val base = iface.subnet24()
        if (base.isEmpty()) {
            onResult(emptyList())
            return
        }
        val found = java.util.Collections.synchronizedList(ArrayList<String>())
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val total = 254
        val pool = Executors.newFixedThreadPool(32)
        try {
            for (host in 1..254) {
                val target = base + host
                if (target == iface.address) continue      // that is this phone
                pool.execute {
                    val socket = Socket()
                    try {
                        socket.connect(InetSocketAddress(target, port), timeoutMs)
                        found.add(target)
                    } catch (e: Exception) {
                        // nothing there, which is the normal answer
                    } finally {
                        try {
                            socket.close()
                        } catch (e: Exception) {
                            // ignore
                        }
                        val n = done.incrementAndGet()
                        // a heartbeat rather than every host, so the UI is not
                        // asked to redraw two hundred times
                        if (n % 16 == 0 || n == total) onProgress(n, total)
                    }
                }
            }
            pool.shutdown()
            // 254 hosts over 32 threads at a short timeout is a few seconds
            pool.awaitTermination(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // fall through and report whatever was found
        } finally {
            pool.shutdownNow()
        }
        onResult(ArrayList(found))
    }
}
