package crazydude.com.telemetry.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.DatagramSocket
import java.net.Socket

/**
 * Pins telemetry sockets to the Wi-Fi network and keeps broadcast traffic
 * flowing.
 *
 * Both matter, and both fail silently when they are missing:
 *
 *  - **Routing.** When the phone joins a transmitter's own access point there
 *    is no internet on it, so Android keeps mobile data as the default route
 *    and sends the app's packets out over the cellular interface, where nothing
 *    is listening. The connection looks established and no data ever arrives.
 *  - **Broadcast.** An ExpressLRS backpack *broadcasts* its telemetry rather
 *    than addressing the phone. Wi-Fi power saving filters broadcast frames
 *    unless something holds a multicast lock.
 *
 * Everything here is best effort: if a lock or a bind is refused the socket is
 * still usable, it simply may not receive on some devices, so nothing throws.
 */
class WifiNetworkBinder(context: Context) {

    companion object {
        private const val TAG = "WifiNetworkBinder"
    }

    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiNetwork: Network? = null
    private var boundProcess = false

    /** Find the Wi-Fi network and take the multicast lock. Safe to call twice. */
    fun acquire() {
        wifiNetwork = findWifiNetwork()

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

        // Below API 23 a socket cannot be bound individually, so the whole
        // process is pointed at Wi-Fi instead and put back on release().
        val network = wifiNetwork
        if (network != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            try {
                @Suppress("DEPRECATION")
                boundProcess = ConnectivityManager.setProcessDefaultNetwork(network)
            } catch (e: Exception) {
                Log.w(TAG, "cannot pin process to wifi: " + e.message)
            }
        }
    }

    fun bind(socket: Socket) {
        val network = wifiNetwork ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                network.bindSocket(socket)
            } catch (e: Exception) {
                Log.w(TAG, "cannot bind tcp socket to wifi: " + e.message)
            }
        }
    }

    fun bind(socket: DatagramSocket) {
        val network = wifiNetwork ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                network.bindSocket(socket)
            } catch (e: Exception) {
                Log.w(TAG, "cannot bind udp socket to wifi: " + e.message)
            }
        }
    }

    fun release() {
        try {
            multicastLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        multicastLock = null

        if (boundProcess) {
            try {
                @Suppress("DEPRECATION")
                ConnectivityManager.setProcessDefaultNetwork(null)
            } catch (e: Exception) {
                // ignore
            }
            boundProcess = false
        }
        wifiNetwork = null
    }

    /** True when the phone is on Wi-Fi at all, so the UI can warn if it is not. */
    fun hasWifi(): Boolean = findWifiNetwork() != null

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
     * The DHCP gateway, which is the single most useful default this dialog
     * can offer: when the phone joins a transmitter's own access point, the
     * gateway *is* the transmitter — 10.0.0.1 for an ExpressLRS backpack — so
     * nobody has to be told an address to type.
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

    private fun findWifiNetwork(): Network? {
        val manager = connectivity ?: return null
        try {
            for (network in manager.allNetworks) {
                val caps = manager.getNetworkCapabilities(network)
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return network
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot enumerate networks: " + e.message)
        }
        return null
    }
}
