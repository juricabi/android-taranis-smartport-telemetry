package crazydude.com.telemetry.protocol.pollers

import android.os.Handler
import android.os.Looper
import crazydude.com.telemetry.protocol.Protocol
import crazydude.com.telemetry.protocol.ProtocolDetector
import crazydude.com.telemetry.protocol.ProtocolFactory
import crazydude.com.telemetry.protocol.decoder.DataDecoder
import crazydude.com.telemetry.utils.WifiNetworkBinder
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

/**
 * Telemetry over the network, from a TCP server or a UDP sender.
 *
 * Both of the things people actually fly with are covered:
 *
 *  - an **ExpressLRS TX backpack** with Telemetry set to WiFi, which
 *    *broadcasts* MAVLink to UDP 14550, so nothing needs to be addressed
 *  - a **TBS Crossfire/Tracer WiFi module**, which is a *server* on TCP 8888 by
 *    default and can be switched to UDP on the module
 *
 * Neither is assumed to carry MAVLink. A TBS transmitter in Serial Bridge mode
 * puts CRSF on the same socket, and in MAVLink Emulator mode it synthesises
 * MAVLink from a Betaflight quad that never spoke it — so the bytes go through
 * the same [ProtocolDetector] the other transports use, and any protocol the
 * app already understands works here for free.
 *
 * Threading follows the existing pollers exactly: everything happens on this
 * class's own thread, connection state is posted to the main thread, and the
 * per byte telemetry callbacks are delivered on the poller thread, which is
 * where they have always come from.
 */
class NetworkDataPoller(
    private val useTcp: Boolean,
    private val host: String,
    private val port: Int,
    private val listener: DataDecoder.Listener,
    private val logFile: FileOutputStream?,
    private val binder: WifiNetworkBinder?
) : DataPoller {

    companion object {
        private const val TCP_CONNECT_TIMEOUT_MS = 8000
        private const val BUFFER = 2048

        /**
         * A MAVLink v1 HEARTBEAT from a ground station: length 9, sysid 255,
         * compid 190, msgid 0, type GCS. Only used to announce ourselves.
         */
        private val HEARTBEAT = byteArrayOf(
            0xFE.toByte(), 9, 0, 0xFF.toByte(), 0xBE.toByte(), 0,
            0, 0, 0, 0, 6, 8, 0xC0.toByte(), 0, 0, 0
        )
    }

    /**
     * Fresh per connection: ProtocolDetector never resets its hit counters, so
     * a reused one would latch whatever it half-recognised during a failed
     * attempt.
     */
    private val detector = ProtocolDetector(object : ProtocolDetector.Callback {
        override fun onProtocolDetected(protocol: Protocol?) {
            // The detector keeps firing on every byte once something has two
            // hits, and it fires for every protocol that reached the threshold,
            // so the first answer wins and the rest are ignored.
            if (selectedProtocol != null) return
            val live = ProtocolFactory.create(protocol, listener)
            if (live == null) {
                finish()
                return
            }
            selectedProtocol = live
            listener.onProtocolDetected(ProtocolFactory.nameOf(live))
        }
    })

    @Volatile
    private var selectedProtocol: Protocol? = null

    @Volatile
    private var connectedOnce = false

    /** Exactly one of onDisconnected/onConnectionFailed must ever be sent. */
    @Volatile
    private var finished = false

    /**
     * Asked to stop.
     *
     * A flag of our own rather than the thread's interrupt status, because the
     * interrupt status is not ours alone: this thread runs the app's telemetry
     * callbacks, and anything down there that catches InterruptedException
     * clears the flag on the way past. While data was arriving the loop was
     * spending its time inside those callbacks, so the interrupt could be
     * swallowed before the loop ever looked at it and the connection would not
     * let go.
     */
    @Volatile
    private var stopping = false

    @Volatile
    private var tcpSocket: Socket? = null

    @Volatile
    private var udpSocket: DatagramSocket? = null

    private val thread: Thread

    init {
        thread = Thread(Runnable {
            try {
                if (useTcp) runTcp() else runUdp()
            } catch (e: Exception) {
                // Anything at all that escapes still has to produce exactly one
                // terminal callback, or DataService.isConnected() stays true
                // forever and the app can never reconnect.
                finish()
            }
        })
        thread.start()
    }

    // ---------------------------------------------------------------- TCP

    private fun runTcp() {
        val socket = Socket()
        tcpSocket = socket
        // Pinning to Wi-Fi is right for a module on its own access point and
        // wrong for anything reachable on this device: a loopback target has no
        // route over the Wi-Fi network, so binding it there fails the connect.
        if (!isLoopback(host)) binder?.bind(socket)
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), TCP_CONNECT_TIMEOUT_MS)

        connectedOnce = true
        runOnMainThread(Runnable { listener.onConnected() })

        val buffer = ByteArray(BUFFER)
        val input = socket.getInputStream()
        while (!stopping && !finished) {
            // -1 is a clean close by the far end. The Bluetooth poller never
            // has to handle this because RFCOMM throws instead, but a TCP peer
            // that goes away politely would otherwise spin here forever.
            val size = input.read(buffer)
            if (size < 0) break
            feed(buffer, 0, size)
        }
        finish()
    }

    // ---------------------------------------------------------------- UDP

    private fun runUdp() {
        // Bound with reuse set before the bind, so a previous session that has
        // not finished closing does not make this one fail.
        val socket = DatagramSocket(null)
        udpSocket = socket
        socket.reuseAddress = true
        socket.broadcast = true
        binder?.bind(socket)
        socket.bind(InetSocketAddress(port))

        connectedOnce = true
        runOnMainThread(Runnable { listener.onConnected() })

        // Some senders unicast back to whoever spoke first rather than
        // broadcasting. ExpressLRS does not need this — it broadcasts — but a
        // mavlink-router in unicast mode does, and an empty datagram is free.
        announce()

        val buffer = ByteArray(BUFFER)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!stopping && !finished) {
            // The length has to be restored every time: receive() shrinks it to
            // the size of the datagram that arrived, and it is never grown back.
            packet.length = buffer.size
            socket.receive(packet)
            feed(packet.data, packet.offset, packet.length)
        }
        finish()
    }

    /**
     * Say hello, so a sender that unicasts back to whoever spoke first knows
     * where to send.
     *
     * A MAVLink heartbeat rather than an empty datagram. Zero length datagrams
     * are legal and cost nothing to send, but plenty of firmware never sees
     * them: the receive loop treats a length of zero as nothing to do and the
     * sender is never registered. A heartbeat is what a ground station sends
     * anyway, so anything expecting a GCS recognises it.
     */
    private fun announce() {
        if (host.isEmpty()) return
        try {
            val addr = InetAddress.getByName(host)
            val socket = udpSocket ?: return
            socket.send(DatagramPacket(HEARTBEAT, HEARTBEAT.size, addr, port))
        } catch (e: IOException) {
            // A sender that broadcasts does not need this, and one that does
            // will simply never be heard from.
        }
    }

    // ---------------------------------------------------------------- shared

    private fun isLoopback(target: String): Boolean {
        return target == "localhost" || target.startsWith("127.") || target == "::1"
    }

    private fun feed(buffer: ByteArray, offset: Int, size: Int) {
        if (size <= 0) return
        logFile?.write(buffer, offset, size)
        for (i in offset until offset + size) {
            // Unsigned. A sign extended byte silently breaks every decoder's
            // state machine and detection then never fires at all.
            val value = buffer[i].toUByte().toInt()
            listener.onTelemetryByte()
            val protocol = selectedProtocol
            if (protocol != null) protocol.process(value) else detector.feedData(value)
        }
        // Once per batch, as with every other transport: this is what makes the
        // map and the telemetry views redraw.
        listener.commit()
    }

    /**
     * The single exit. Whether this was a failure or a disconnect is decided by
     * whether the connection ever came up, exactly as the Bluetooth poller
     * decides it — the caller does not get to say.
     */
    private fun finish() {
        if (finished) return
        finished = true
        closeQuietly()
        try {
            logFile?.close()
        } catch (e: IOException) {
            // ignore
        }
        val connected = connectedOnce
        runOnMainThread(Runnable {
            if (connected) listener.onDisconnected() else listener.onConnectionFailed()
        })
    }

    private fun closeQuietly() {
        try {
            tcpSocket?.close()
        } catch (e: IOException) {
            // ignore
        }
        try {
            udpSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        binder?.release()
    }

    private fun runOnMainThread(runnable: Runnable) {
        Handler(Looper.getMainLooper()).post { runnable.run() }
    }

    /**
     * Called on the main thread by DataService, so it must not block. Closing
     * the socket is what unblocks the reading thread — interrupting it is not
     * enough, because a blocking socket read ignores interruption.
     */
    override fun disconnect() {
        stopping = true
        thread.interrupt()
        // Closing is what unblocks a thread parked in receive() or read(); the
        // flag is what stops one that is busy handling data.
        try {
            tcpSocket?.close()
        } catch (e: IOException) {
            // ignore
        }
        try {
            udpSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        // Report it here rather than waiting for the thread to notice and do
        // it: finish() is latched, so whichever gets there first wins and the
        // other is a no-op. Without this the UI sat on "Disconnecting…" for as
        // long as the thread took to come back round.
        finish()
    }
}
