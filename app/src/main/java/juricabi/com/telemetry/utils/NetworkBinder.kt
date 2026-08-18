package juricabi.com.telemetry.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/**
 * Puts each stream socket on the network that actually reaches its target,
 * and holds a multicast lock.
 *
 * A transmitter's access point, a goggle's Wi-Fi, a USB-ethernet adapter — a
 * link that carries a module usually carries no internet, and with mobile data
 * preferred it loses the default route. Every connect then leaves over
 * cellular and hangs. The pin is not "use Wi-Fi": the network is chosen by its
 * own routing table — whichever one holds the most specific route covering the
 * target's address carries that stream. Cellular only ever offers the
 * catch-all default route, so an internet target — the map tiles — is never
 * captured and rides whatever the phone prefers. And when this phone is the
 * hotspot, its clients live on a local interface no pin is needed for; no
 * network matches, nothing is bound, delivery is the system's own.
 *
 * Without the lock, Wi-Fi power saving drops the broadcasts an ExpressLRS
 * backpack sends.
 *
 * All best effort - nothing here throws.
 */
class NetworkBinder(context: Context) {

    companion object {
        private const val TAG = "NetworkBinder"
    }

    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?

    private var multicastLock: WifiManager.MulticastLock? = null

    fun acquire() {
        if (multicastLock == null) {
            try {
                val wifi =
                    appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
                val lock = wifi?.createMulticastLock("telemetry-udp")
                lock?.setReferenceCounted(false)
                lock?.acquire()
                multicastLock = lock
            } catch (e: Exception) {
                // Missing CHANGE_WIFI_MULTICAST_STATE, or a vendor that refuses:
                // unicast still works, so carry on.
                Log.w(TAG, "no multicast lock: " + e.message)
            }
        }
    }

    fun bind(socket: Socket, host: String) {
        val network = networkFor(host) ?: return
        try {
            network.bindSocket(socket)
        } catch (e: Exception) {
            Log.w(TAG, "cannot bind tcp socket: " + e.message)
        }
    }

    fun bind(socket: DatagramSocket, host: String) {
        // A listener with no address to dial, and a broadcast target no
        // subnet route contains, both belong on the local link the modules
        // live on: unbound they still hear everyone, but their own sends —
        // the heartbeats and announces a learned peer is kept alive with —
        // would leave over the default route and vanish into mobile data.
        val network = (if (host.isBlank() || host == "255.255.255.255")
            localNetwork() else networkFor(host)) ?: return
        try {
            network.bindSocket(socket)
        } catch (e: Exception) {
            Log.w(TAG, "cannot bind udp socket: " + e.message)
        }
    }

    /**
     * The network that reaches [host], for a stack that opens its own sockets
     * and never hands them here — media3's RTSP client, an HTTP connection.
     * Null when nothing specifically routes there, leaving the stack on its
     * default.
     */
    fun networkTo(host: String): Network? = networkFor(host)

    fun release() {
        try {
            multicastLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        multicastLock = null
    }

    /** True when the phone is on Wi-Fi at all, so the UI can warn if it is not. */
    fun hasWifi(): Boolean {
        val manager = connectivity ?: return false
        try {
            for (network in manager.allNetworks) {
                val caps = manager.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                ) return true
            }
        } catch (e: Exception) {
            // fall through
        }
        return false
    }

    /** The Wi-Fi network's name, for showing which one is about to be used. */
    fun ssid(): String? {
        return try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
            val name = wifi?.connectionInfo?.ssid ?: return null
            // the framework hands it back quoted
            val trimmed = name.trim('"')
            if (trimmed.isEmpty() || trimmed == "<unknown ssid>") null else trimmed
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The DHCP gateway: when the phone joins a transmitter's access point, the
     * gateway is the transmitter, so nobody has to be told what to type.
     */
    fun gatewayAddress(): String? {
        return try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
            val gateway = wifi?.dhcpInfo?.gateway ?: return null
            if (gateway == 0) return null
            // DhcpInfo keeps addresses little endian
            ((gateway and 0xFF).toString() + "." +
                ((gateway shr 8) and 0xFF) + "." +
                ((gateway shr 16) and 0xFF) + "." +
                ((gateway shr 24) and 0xFF))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The interfaces carrying mobile data, asked of the system rather than
     * guessed: rmnet, ccmni, pdp and clat are all vendor conventions.
     */
    fun cellularInterfaceNames(): Set<String> {
        val names = HashSet<String>()
        val manager = connectivity ?: return names
        try {
            for (network in manager.allNetworks) {
                val caps = manager.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
                val link = manager.getLinkProperties(network) ?: continue
                link.interfaceName?.let { names.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot identify mobile interfaces: " + e.message)
        }
        return names
    }

    /**
     * The network whose routing table most specifically covers [host]: a
     * longest-prefix match across every network the phone holds, VPNs left out
     * (a VPN reports the transports of whatever it runs over, and pinning into
     * the tunnel sends the stream somewhere it can never arrive). The
     * catch-all default route never wins — matching it means only the
     * internet reaches the target, and the default network does that best
     * unpinned. Loopback needs no pin, and a hostname is not resolved here:
     * this may run on the main thread, where a DNS lookup is forbidden, and
     * every module this exists for is dialled by numeric address anyway.
     */
    private fun networkFor(host: String): Network? {
        val manager = connectivity ?: return null
        // Numeric literals only. A dotted quad or a colon'd IPv6 parses without
        // DNS; a hostname would resolve, and this can run on the main thread
        // where that throws — and a hostname target is an internet one anyway,
        // which no local route captures. Every module this exists for is
        // dialled by numeric address.
        val numeric = host.contains(':') || host.matches(Regex("(\\d{1,3}\\.){3}\\d{1,3}"))
        if (!numeric) return null
        val address = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            return null
        }
        if (address.isLoopbackAddress) return null
        var best: Network? = null
        var bestPrefix = 0 // strictly more specific than the default route
        try {
            for (network in manager.allNetworks) {
                val caps = manager.getNetworkCapabilities(network) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
                val link = manager.getLinkProperties(network) ?: continue
                for (route in link.routes) {
                    val destination = route.destination
                    if (destination.prefixLength > bestPrefix &&
                        destination.contains(address)
                    ) {
                        bestPrefix = destination.prefixLength
                        best = network
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot enumerate networks: " + e.message)
        }
        if (best != null) return best
        // A private address nothing specifically routes to — a module one
        // subnet behind the Wi-Fi gateway, say — can only be meant for the
        // local link: cellular never carries one, so the local network's own
        // default route is the answer. A public address stays unpinned; the
        // phone's preferred network reaches the internet best.
        return if (address.isSiteLocalAddress || address.isLinkLocalAddress)
            localNetwork() else null
    }

    /**
     * The network a local module lives on when no route names it: Wi-Fi
     * first, then anything else that is neither cellular nor a VPN — a
     * USB-ethernet adapter. Null on cellular alone, and null when this phone
     * is the hotspot, whose clients the system reaches without a pin.
     */
    private fun localNetwork(): Network? {
        val manager = connectivity ?: return null
        var other: Network? = null
        try {
            for (network in manager.allNetworks) {
                val caps = manager.getNetworkCapabilities(network) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return network
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) other = network
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot enumerate networks: " + e.message)
        }
        return other
    }
}
