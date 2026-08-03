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
import android.util.Base64
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

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
    private val mode: Int,
    private val host: String,
    private val port: Int,
    private val listener: DataDecoder.Listener,
    private val logFile: FileOutputStream?,
    private val binder: WifiNetworkBinder?
) : DataPoller {

    companion object {
        private const val TCP_CONNECT_TIMEOUT_MS = 8000
        private const val BUFFER = 2048
        /** Listen for datagrams: a module that broadcasts, or a router. */
        const val MODE_UDP = 0

        /** Dial out: a TBS WiFi module or a serial-to-WiFi bridge is a server. */
        const val MODE_TCP_CLIENT = 1

        /**
         * Wait for the other end to dial in.
         *
         * The remaining common shape: mavlink-router's tcp server, and bridges
         * configured to push rather than serve. Without it those setups have no
         * way to reach the app at all.
         */
        const val MODE_TCP_SERVER = 2

        /**
         * A TBS Crossfire WiFi module's CRSF WebSocket, ws://<ip>/ws.
         *
         * Worth having as its own transport because it is the only way into
         * that module that works on every firmware: the MQTT path its own phone
         * app uses needs a broker running in the app and is broken on 3.20,
         * while /ws served CRSF on 2.25, 3.0, 3.10 and 3.20 alike. What comes
         * out of it is ordinary CRSF, so the existing decoder handles it.
         */
        const val MODE_WEBSOCKET = 3

        private const val RECEIVE_TIMEOUT_MS = 1000
        private const val ANNOUNCE_EVERY_MS = 1000L
        private const val MAX_PEERS = 4
        private const val MAX_FRAME = 1 shl 20
        private const val DEVICE_PING_EVERY_MS = 5000L

        private const val OPCODE_CONTINUATION = 0x0
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_BINARY = 0x2
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA

        /**
         * CRSF device ping, broadcast, from the radio address: sync, length,
         * type 0x28, destination, origin, CRC8 over the body with poly 0xD5.
         */
        private val DEVICE_PING = byteArrayOf(
            0xC8.toByte(), 0x04, 0x28, 0x00, 0xEA.toByte(), 0x54
        )

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
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var udpSocket: DatagramSocket? = null

    /**
     * Everyone we have heard from on this socket.
     *
     * Taken from QGroundControl, which adds the sender of every datagram to its
     * session targets and then talks to all of them. It matters because the
     * address field is deliberately empty for a UDP listen — there is nothing
     * to type when a module broadcasts — and a source that wants to be spoken
     * to would otherwise never hear from us. Once anything arrives we know
     * where it came from, so we can keep it alive.
     */
    private val peers = LinkedHashSet<Pair<InetAddress, Int>>()

    private val thread: Thread

    init {
        thread = Thread(Runnable {
            try {
                when (mode) {
                    MODE_TCP_CLIENT -> runTcp()
                    MODE_TCP_SERVER -> runTcpServer()
                    MODE_WEBSOCKET -> runWebSocket()
                    else -> runUdp()
                }
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

    private fun runTcpServer() {
        val server = ServerSocket()
        serverSocket = server
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))

        // Bound and waiting counts as connected: there is nothing else the user
        // can do, and reporting it lets the service go foreground and the UI
        // stop saying "connecting".
        connectedOnce = true
        runOnMainThread(Runnable { listener.onConnected() })

        server.soTimeout = RECEIVE_TIMEOUT_MS
        while (!stopping && !finished) {
            val client = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                continue
            }
            tcpSocket = client
            client.tcpNoDelay = true
            val buffer = ByteArray(BUFFER)
            val input = client.getInputStream()
            try {
                while (!stopping && !finished) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    feed(buffer, 0, size)
                }
            } catch (e: IOException) {
                // that client went away; keep the door open for the next one
            }
            try {
                client.close()
            } catch (e: IOException) {
                // ignore
            }
            tcpSocket = null
        }
        finish()
    }

    // ---------------------------------------------------------- WebSocket

    private fun runWebSocket() {
        val socket = Socket()
        tcpSocket = socket
        if (!isLoopback(host)) binder?.bind(socket)
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), TCP_CONNECT_TIMEOUT_MS)

        val key = Base64.encodeToString(randomBytes(16), Base64.NO_WRAP)
        val crlf = "\r\n"
        val request = "GET /ws HTTP/1.1" + crlf +
            "Host: " + host + crlf +
            "Upgrade: websocket" + crlf +
            "Connection: Upgrade" + crlf +
            "Sec-WebSocket-Key: " + key + crlf +
            "Sec-WebSocket-Version: 13" + crlf + crlf
        val out = socket.getOutputStream()
        out.write(request.toByteArray())
        out.flush()

        val input = socket.getInputStream()
        val status = readHandshake(input)
        if (!status.contains(" 101")) {
            // not a WebSocket endpoint: fail rather than feed HTML to a decoder
            finish()
            return
        }

        connectedOnce = true
        runOnMainThread(Runnable { listener.onConnected() })

        // The module answers rather than volunteers: without a device ping the
        // socket opens and stays silent forever.
        writeFrame(out, OPCODE_BINARY, DEVICE_PING)

        var lastPing = System.currentTimeMillis()
        while (!stopping && !finished) {
            // Ask again now and then: the first ping is answered only if the
            // module was ready for it, and the name it replies with is what
            // tells a Crossfire from an ExpressLRS.
            val now = System.currentTimeMillis()
            if (now - lastPing >= DEVICE_PING_EVERY_MS) {
                lastPing = now
                writeFrame(out, OPCODE_BINARY, DEVICE_PING)
            }
            val frame = readFrame(input) ?: break
            when (frame.first) {
                OPCODE_BINARY, OPCODE_TEXT, OPCODE_CONTINUATION ->
                    feed(frame.second, 0, frame.second.size)
                OPCODE_PING -> writeFrame(out, OPCODE_PONG, frame.second)
                OPCODE_CLOSE -> {
                    stopping = true
                }
            }
        }
        finish()
    }

    private fun randomBytes(n: Int): ByteArray {
        val out = ByteArray(n)
        java.util.Random(System.currentTimeMillis()).nextBytes(out)
        return out
    }

    private fun readHandshake(input: InputStream): String {
        val sb = StringBuilder()
        val endOfHeaders = "\r\n\r\n"
        while (sb.length < 4096 && !sb.endsWith(endOfHeaders)) {
            val c = input.read()
            if (c < 0) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(out, read, n - read)
            if (r < 0) return null
            read += r
        }
        return out
    }

    /** opcode to payload, or null when the stream ends. */
    private fun readFrame(input: InputStream): Pair<Int, ByteArray>? {
        val head = readFully(input, 2) ?: return null
        val opcode = head[0].toInt() and 0x0F
        val masked = (head[1].toInt() and 0x80) != 0
        var length = (head[1].toInt() and 0x7F).toLong()
        if (length == 126L) {
            val ext = readFully(input, 2) ?: return null
            length = ((ext[0].toInt() and 0xFF).toLong() shl 8) or (ext[1].toInt() and 0xFF).toLong()
        } else if (length == 127L) {
            val ext = readFully(input, 8) ?: return null
            length = 0
            for (b in ext) length = (length shl 8) or (b.toInt() and 0xFF).toLong()
        }
        // a server must not mask, but tolerate it rather than desynchronise
        val mask = if (masked) readFully(input, 4) ?: return null else null
        if (length > MAX_FRAME) return null
        val payload = readFully(input, length.toInt()) ?: return null
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return Pair(opcode, payload)
    }

    /** Client frames must always be masked, says RFC 6455. */
    private fun writeFrame(out: java.io.OutputStream, opcode: Int, payload: ByteArray) {
        val mask = randomBytes(4)
        val header = java.io.ByteArrayOutputStream()
        header.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> header.write(0x80 or n)
            n < 65536 -> {
                header.write(0x80 or 126); header.write((n shr 8) and 0xFF); header.write(n and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
                    header.write((n ushr shift) and 0xFF)
                }
            }
        }
        header.write(mask)
        val masked = ByteArray(n)
        for (i in 0 until n) masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        synchronized(this) {
            out.write(header.toByteArray())
            out.write(masked)
            out.flush()
        }
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

        // Keep saying hello for as long as we are listening.
        //
        // A sender that unicasts points itself at whoever spoke to it most
        // recently, so announcing once at connect is not enough: anything else
        // that talks to the module — another phone, a ground station on a
        // laptop — takes the stream away and this one is starved until it is
        // restarted. A ground station sends heartbeats continuously for exactly
        // this reason, so this does the same and recovers on its own.
        //
        // The timeout is what makes it possible: without it receive() blocks
        // forever and there is never a moment to re-announce.
        socket.soTimeout = RECEIVE_TIMEOUT_MS
        var lastAnnounce = 0L
        val buffer = ByteArray(BUFFER)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!stopping && !finished) {
            val now = System.currentTimeMillis()
            if (now - lastAnnounce >= ANNOUNCE_EVERY_MS) {
                lastAnnounce = now
                announce()
            }
            // The length has to be restored every time: receive() shrinks it to
            // the size of the datagram that arrived, and it is never grown back.
            packet.length = buffer.size
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                // Nothing arrived in the last second. That is not a failure —
                // it is the gap in which we announce ourselves again.
                continue
            }
            rememberPeer(packet.address, packet.port)
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
    private fun rememberPeer(address: InetAddress?, senderPort: Int) {
        if (address == null || senderPort <= 0) return
        synchronized(peers) {
            val key = Pair(address, senderPort)
            if (peers.contains(key)) return
            // bounded: a broadcast network could otherwise introduce us to
            // every device on it
            if (peers.size >= MAX_PEERS) {
                val oldest = peers.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
            peers.add(key)
        }
    }

    /**
     * Say hello to the configured address and to everyone we have heard from.
     *
     * A source that broadcasts does not need any of this. One that unicasts
     * only to whoever spoke to it does, and for those the address field is
     * usually empty because there was nothing to type — so the sender learned
     * from an arriving datagram is the only address we will ever have.
     */
    private fun announce() {
        val socket = udpSocket ?: return
        val targets = ArrayList<Pair<InetAddress, Int>>()
        if (host.isNotEmpty()) {
            try {
                targets.add(Pair(InetAddress.getByName(host), port))
            } catch (e: IOException) {
                // an unresolvable address is not worth failing the connection
            }
        }
        synchronized(peers) { targets.addAll(peers) }
        for (target in targets) {
            try {
                socket.send(DatagramPacket(HEARTBEAT, HEARTBEAT.size, target.first, target.second))
            } catch (e: IOException) {
                // one unreachable peer must not stop the others
            }
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
        try {
            serverSocket?.close()
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
        try {
            serverSocket?.close()
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
