package juricabi.com.telemetry.protocol.pollers

import android.os.Handler
import android.os.Looper
import juricabi.com.telemetry.protocol.Protocol
import juricabi.com.telemetry.protocol.ProtocolDetector
import juricabi.com.telemetry.protocol.ProtocolFactory
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import juricabi.com.telemetry.utils.WifiNetworkBinder
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
 * Telemetry over the network: UDP, TCP either way round, or a WebSocket.
 *
 * The protocol is detected from the bytes as on every other transport, so
 * whatever the module puts on the socket is understood — CRSF from a serial
 * bridge, MAVLink from a backpack.
 *
 * Threading matches the other pollers: one thread of its own, connection state
 * posted to the main thread, telemetry callbacks on the poller thread.
 *
 * A quiet link is never taken for a dead one — a module with no receiver bound
 * looks exactly like one that lost power, so only a close or an error ends a
 * connection.
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

        /** Wait for the other end to dial in: mavlink-router, pushing bridges. */
        const val MODE_TCP_SERVER = 2

        /**
         * A TBS Crossfire WiFi module's CRSF WebSocket - the only way into that
         * module that works on every firmware, unlike the MQTT path its own app
         * uses.
         */
        const val MODE_WEBSOCKET = 3

        private const val RECEIVE_TIMEOUT_MS = 1000
        private const val HANDSHAKE_TIMEOUT_MS = 8000
        private const val ANNOUNCE_EVERY_MS = 1000L
        private const val MAX_PEERS = 4

        /** A datagram arrives whole or truncated, so hold the largest one. */
        private const val DATAGRAM_BUFFER = 65535

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
         * compid 190, msgid 0, type GCS, ending in its two byte checksum.
         * Only used to announce ourselves; a receiver that checks the checksum
         * before registering a sender would drop it without one.
         */
        private val HEARTBEAT = byteArrayOf(
            0xFE.toByte(), 9, 0, 0xFF.toByte(), 0xBE.toByte(), 0,
            0, 0, 0, 0, 6, 8, 0xC0.toByte(), 0, 0,
            0x99.toByte(), 0x53
        )
    }

    /** Fresh per connection: the detector never resets its hit counters. */
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
    private val finished = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Asked to stop. Ours rather than the thread's interrupt status, which the
     * telemetry callbacks this thread runs can swallow - and did, leaving a
     * busy link impossible to disconnect.
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
     * Everyone we have heard from, as QGroundControl does it: a UDP listen has
     * no address to type, so the sender of an arriving datagram is the only
     * address we will ever have for talking back.
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
        thread.name = "telemetry-net"
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

        // No receive timeout here on purpose: there is nothing this loop would
        // do with the wakeup. Stopping already unblocks it, by closing the
        // socket out from under the read.
        val buffer = ByteArray(BUFFER)
        val input = socket.getInputStream()
        while (!stopping && !finished.get()) {
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
        while (!stopping && !finished.get()) {
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
                while (!stopping && !finished.get()) {
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

        // before the handshake read, not after: a peer that accepts and then
        // says nothing would otherwise block here forever, with no terminal
        // callback and the button stuck on "Connecting..."
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS

        val input = socket.getInputStream()
        val status = readHandshake(input)
        // the status line only: an ordinary error page can carry " 101" in its
        // headers, and its body would then be parsed as frames
        if (!status.substringBefore(crlf).contains(" 101 ")) {
            // not a WebSocket endpoint: fail rather than feed HTML to a decoder
            finish()
            return
        }

        connectedOnce = true
        runOnMainThread(Runnable { listener.onConnected() })

        // The module answers rather than volunteers: without a device ping the
        // socket opens and stays silent forever.
        writeFrame(out, OPCODE_BINARY, DEVICE_PING)

        // shorter now: the repeat ping below cannot fire while readFrame blocks
        socket.soTimeout = RECEIVE_TIMEOUT_MS

        var lastPing = System.currentTimeMillis()
        while (!stopping && !finished.get()) {
            // Ask again now and then: the first ping is answered only if the
            // module was ready for it, and the name it replies with is what
            // tells a Crossfire from an ExpressLRS.
            val now = System.currentTimeMillis()
            if (now - lastPing >= DEVICE_PING_EVERY_MS) {
                lastPing = now
                writeFrame(out, OPCODE_BINARY, DEVICE_PING)
            }
            val frame = try {
                readFrame(input)
            } catch (e: SocketTimeoutException) {
                // Nothing this second, which is ordinary: a module with no
                // receiver bound answers the ping and otherwise says nothing.
                continue
            } ?: break
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

    /**
     * One generator, kept. Seeding a new one from the clock on every call gave
     * the same mask to every frame written within the same millisecond, which
     * is the one thing a mask is not supposed to be.
     */
    private val random = java.util.Random()

    private fun randomBytes(n: Int): ByteArray {
        val out = ByteArray(n)
        random.nextBytes(out)
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

    /**
     * Read exactly [n] bytes. A timeout part way through a frame means the rest
     * is still coming, and giving up would lose bytes the stream cannot
     * resynchronise from - so it may only escape at a frame boundary, which is
     * what [mayTimeOut] marks.
     */
    private fun readFully(input: InputStream, n: Int, mayTimeOut: Boolean = false): ByteArray? {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = try {
                input.read(out, read, n - read)
            } catch (e: SocketTimeoutException) {
                if (read == 0 && mayTimeOut) throw e
                if (stopping || finished.get()) return null
                continue
            }
            if (r < 0) return null
            read += r
        }
        return out
    }

    /**
     * opcode to payload, null when the stream ends, or SocketTimeoutException
     * when no frame has begun to arrive.
     */
    private fun readFrame(input: InputStream): Pair<Int, ByteArray>? {
        val head = readFully(input, 2, mayTimeOut = true) ?: return null
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
        if (length < 0 || length > MAX_FRAME) return null
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
        if (!isLoopback(host)) binder?.bind(socket)
        socket.bind(InetSocketAddress(port))

        // Room for a burst to sit in while we are busy decoding the last one.
        // A request, not a demand: the system may give less, and does not fail.
        try {
            socket.receiveBufferSize = 256 * 1024
        } catch (e: SocketException) {
            // ignore
        }

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
        val buffer = ByteArray(DATAGRAM_BUFFER)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!stopping && !finished.get()) {
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
     * Remember a sender so we can talk back to it. A heartbeat rather than an
     * empty datagram, because plenty of firmware ignores a zero length one.
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

    /** Say hello, for a source that only unicasts back to whoever spoke to it. */
    /** Resolved once: getByName can block for seconds, and this runs every second. */
    private var announceHost: InetAddress? = null
    private var announceHostResolved = false

    private fun announce() {
        val socket = udpSocket ?: return

        // Only while the stream might be MAVLink. Once it is known to be
        // something else, this is a ground station heartbeat being written into
        // a link that never asked for one — and a TBS module in bridge mode puts
        // whatever arrives straight onto the CRSF serial link.
        val live = selectedProtocol
        if (live != null && live !is juricabi.com.telemetry.protocol.MAVLinkProtocol &&
            live !is juricabi.com.telemetry.protocol.MAVLink2Protocol
        ) {
            return
        }

        val targets = ArrayList<Pair<InetAddress, Int>>()
        if (host.isNotEmpty() && !isLoopback(host)) {
            if (!announceHostResolved) {
                announceHostResolved = true
                announceHost = try {
                    InetAddress.getByName(host)
                } catch (e: IOException) {
                    // an unresolvable address is not worth failing the connection
                    null
                }
            }
            announceHost?.let { targets.add(Pair(it, port)) }
        }
        synchronized(peers) { targets.addAll(peers) }
        // Nobody to talk to yet is a perfectly ordinary state: it means nothing
        // has arrived. Broadcasting a hello at the whole network to try to shake
        // something loose is not this app's business — QGroundControl does not
        // do it either, and every module we have met announces itself.
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
        try {
            logFile?.write(buffer, offset, size)
        } catch (e: IOException) {
            // A full card must not take the link down with it. Nothing else
            // depends on the log, and someone flying is better served by
            // telemetry that keeps working than by a recording of it.
        }
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

    /** The single exit; failure or disconnect is decided by whether it came up. */
    private fun finish() {
        if (!finished.compareAndSet(false, true)) return
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

    /** Main thread, so it must not block: closing the socket unblocks the reader. */
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
