package juricabi.com.telemetry.protocol.pollers

import android.os.Handler
import android.os.Looper
import juricabi.com.telemetry.protocol.Protocol
import juricabi.com.telemetry.protocol.ProtocolDetector
import juricabi.com.telemetry.protocol.ProtocolFactory
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The pipeline every stream transport shares. The transport is the adapter —
 * it opens, reads and closes; everything between the raw byte and the decoder,
 * and between trouble and the single terminal callback, lives here. This used
 * to be a copy in each poller, and the copies drifted: USB delivered its
 * terminal callbacks on its serial thread while the others posted to main.
 *
 * The contract, in one place at last:
 *  - raw bytes reach the best-effort log before any decoding
 *  - detection races the known protocols until one has two clean decodes,
 *    then that protocol is selected and named to the listener
 *  - every byte announces itself (onTelemetryByte); every batch ends in one
 *    commit — that is what makes the map and the readouts redraw
 *  - exactly one terminal callback ever, failed or disconnected decided by
 *    whether the link came up, posted to the main thread
 *  - telemetry callbacks stay on the transport's own thread
 *
 * BluetoothLeDataPoller keeps its own copy of this machinery on purpose: its
 * bytes arrive as a race between GATT characteristics, not as one stream.
 */
class PollerChassis(
    private val listener: DataDecoder.Listener,
    logFile: FileOutputStream?,
    /** The transport's teardown — close what open made, unblock its reader. Runs exactly once. */
    private val shutdown: () -> Unit
) {

    private val log = BestEffortLog(logFile)
    private val finished = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var connectedOnce = false

    @Volatile
    private var selectedProtocol: Protocol? = null

    /** The live protocol, for a transport with protocol-shaped duties (announce). */
    val protocol: Protocol? get() = selectedProtocol

    /** Whether the single exit has been taken. Loops watch this. */
    val done: Boolean get() = finished.get()

    private val detector = ProtocolDetector(object : ProtocolDetector.Callback {
        override fun onProtocolDetected(protocol: Protocol?) {
            val live = ProtocolFactory.create(protocol, listener)
            if (live == null) {
                // the detector only offers protocols the factory knows, so
                // this is the stream turning out to be nothing decodable
                finish(connectionFailed = true)
                return
            }
            select(live)
        }
    })

    /** The link is up. Said once; the callback rides the main thread. */
    fun connected() {
        connectedOnce = true
        mainHandler.post { listener.onConnected() }
    }

    /**
     * A protocol chosen without detection — MAVLink High Latency pins its
     * own, under its own reported name.
     */
    fun select(live: Protocol, name: String = ProtocolFactory.nameOf(live)) {
        selectedProtocol = live
        listener.onProtocolDetected(name)
    }

    fun feed(buffer: ByteArray, offset: Int = 0, size: Int = buffer.size) {
        if (size <= 0 || finished.get()) return
        log.write(buffer, offset, size)
        for (i in offset until offset + size) {
            if (finished.get()) return
            listener.onTelemetryByte()
            // Unsigned. A sign extended byte silently breaks every decoder's
            // state machine and detection then never fires at all.
            val value = buffer[i].toUByte().toInt()
            val protocol = selectedProtocol
            if (protocol != null) protocol.process(value) else detector.feedData(value)
        }
        if (!finished.get()) listener.commit()
    }

    /** The single exit; failure or disconnect is decided by whether it came up. */
    fun finish(connectionFailed: Boolean = !connectedOnce) {
        if (!finished.compareAndSet(false, true)) return
        shutdown()
        log.close()
        mainHandler.post {
            if (connectionFailed) listener.onConnectionFailed() else listener.onDisconnected()
        }
    }
}
