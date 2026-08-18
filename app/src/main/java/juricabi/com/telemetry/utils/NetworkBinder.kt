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
        // Numeric literals only, and strictly: getByName resolves anything
        // else, which is a DNS lookup — forbidden on the main thread, where
        // this runs — and a name is an internet target anyway, which no local
        // route captures. Octets are range-checked so "999.999.999.999" is a
        // name, not an address, and an IPv6 literal must carry the two colons
        // every one of them has, so a stray "192.168.4.1:14550" pasted into
        // the host field is refused here rather than looked up.
        val bare = host.trim('[', ']').substringBefore('%')
        val quad = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})")
        val numeric = when {
            bare.count { it == ':' } >= 2 -> bare.all { it.isDigit() || it in "abcdefABCDEF:." }
            else -> quad.matchEntire(bare)
                ?.groupValues?.drop(1)?.all { (it.toIntOrNull() ?: 256) <= 255 } == true
        }
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
        if (best != null) return if (bindable(best)) best else null
        // Directly attached, but on an interface ConnectivityManager does not
        // model as a network — this phone's own hotspot above all, which it
        // cannot see at all, and the vendor ap/p2p/tether interfaces beside it.
        // The system's own local routing delivers to a neighbour on such a
        // link; pinning instead hands the packet to some other network's
        // gateway, and the module that Find just listed goes unreachable.
        if (localPrefixHolds(address)) return null
        // A private address nothing specifically routes to — a module one
        // subnet behind the Wi-Fi gateway, say — can only be meant for the
        // local link: cellular never carries one, so the local network's own
        // default route is the answer. Unless a VPN is up: a private address
        // may then live across the tunnel — a camera at home dialled through
        // WireGuard — and the tunnel is the default road; grabbing the socket
        // onto the local link would send it where that subnet does not exist.
        // Unbound, the system's own routing hands it to the tunnel. (A target
        // the local link specifically routes to, the goggle on its Wi-Fi, is
        // matched above and stays pinned even with a VPN running.) A public
        // address stays unpinned always; the phone's preferred network
        // reaches the internet best.
        return if (!vpnActive() && (address.isSiteLocalAddress || address.isLinkLocalAddress))
            localNetwork()?.takeIf { bindable(it) } else null
    }

    /**
     * Whether the system will let this app bind to [network] at all, asked
     * with a throwaway socket. A VPN that captures this app's traffic refuses
     * binds around itself — EPERM — and a network mid-vanish refuses too.
     * Handing media3 a factory it cannot bind fails every connect with
     * "operation not permitted", where surrendering the pin lets the stream
     * ride the tunnel, which reaches even a local subnet by hairpinning
     * through the VPN's own server — the working road the pilot on their
     * home Wi-Fi with the VPN still up actually has.
     */
    private fun bindable(network: Network): Boolean {
        return try {
            val probe = DatagramSocket()
            try {
                network.bindSocket(probe)
                true
            } finally {
                probe.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "network refuses binds, leaving unpinned: " + e.message)
            false
        }
    }

    /**
     * Whether a VPN carries THIS app's traffic — the app's own active network
     * being the tunnel. A VPN merely running somewhere is not the question: a
     * split tunnel that excludes this app leaves it on the plain networks, and
     * treating that as captured would drop the pin a module still needs.
     */
    private fun vpnActive(): Boolean {
        val manager = connectivity ?: return false
        return try {
            val active = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(active) ?: return false
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Whether [address] sits inside one of this phone's own interface prefixes.
     * Asked of the interfaces directly, because that is the only place a
     * hotspot appears at all. A network the system models was already matched
     * by route above, so reaching here means the link is attached but unnamed.
     */
    private fun localPrefixHolds(address: InetAddress): Boolean {
        val target = address.address ?: return false
        for (iface in LocalNetworks.list()) {
            val mine = try {
                InetAddress.getByName(iface.address).address ?: continue
            } catch (e: Exception) {
                continue
            }
            if (mine.size != target.size) continue
            if (iface.prefix <= 0 || iface.prefix > mine.size * 8) continue
            var bits = iface.prefix
            var same = true
            for (i in mine.indices) {
                if (bits <= 0) break
                val take = if (bits >= 8) 8 else bits
                val mask = (0xFF shl (8 - take)) and 0xFF
                if ((mine[i].toInt() and mask) != (target[i].toInt() and mask)) {
                    same = false
                    break
                }
                bits -= take
            }
            if (same) return true
        }
        return false
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
