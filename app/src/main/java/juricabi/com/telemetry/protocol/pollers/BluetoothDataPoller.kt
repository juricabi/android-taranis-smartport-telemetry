package juricabi.com.telemetry.protocol.pollers

import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import juricabi.com.telemetry.protocol.*
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothDataPoller(
    private val bluetoothSocket: BluetoothSocket,
    private val listener: DataDecoder.Listener,
    outputStream: FileOutputStream?
) : DataPoller {

    private val log = BestEffortLog(outputStream)
    private var selectedProtocol: Protocol? = null
    private lateinit var thread: Thread
    private val finished = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var connectedOnce = false

    init {
        thread = Thread(Runnable {
            try {
                bluetoothSocket.connect()
                if (!bluetoothSocket.isConnected) {
                    finish(connectionFailed = true)
                    return@Runnable
                }

                connectedOnce = true
                runOnMainThread(Runnable {
                    listener.onConnected()
                })

                val protocolDetector =
                    ProtocolDetector(object :
                        ProtocolDetector.Callback {
                        override fun onProtocolDetected(protocol: Protocol?) {
                            val live = ProtocolFactory.create(protocol, listener)
                            if (live == null) {
                                finish(connectionFailed = true)
                                return
                            }
                            selectedProtocol = live
                            listener.onProtocolDetected(ProtocolFactory.nameOf(live))
                        }
                    })
                val buffer = ByteArray(1024)
                while (!finished.get()) {
                    val size = bluetoothSocket.inputStream.read(buffer)
                    if (size == -1) {
                        finish(connectionFailed = !connectedOnce)
                        return@Runnable
                    }
                    if (size == 0) continue

                    log.write(buffer, 0, size)
                    for (i in 0 until size) {
                        if (finished.get()) return@Runnable
                        listener.onTelemetryByte()
                        if (selectedProtocol == null) {
                            protocolDetector.feedData(buffer[i].toUByte().toInt())
                        } else {
                            selectedProtocol?.process(buffer[i].toUByte().toInt())
                        }
                    }
                    if (!finished.get()) listener.commit()
                }
            } catch (e: Exception) {
                finish(connectionFailed = !connectedOnce)
            }
        })

        thread.start()
    }

    private fun runOnMainThread(runnable: Runnable) {
        mainHandler.post(runnable)
    }

    private fun finish(connectionFailed: Boolean) {
        if (!finished.compareAndSet(false, true)) return

        thread.interrupt()
        log.close()
        try {
            bluetoothSocket.close()
        } catch (_: IOException) {
        }

        runOnMainThread(Runnable {
            if (connectionFailed) {
                listener.onConnectionFailed()
            } else {
                listener.onDisconnected()
            }
        })
    }

    override fun disconnect() {
        finish(connectionFailed = !connectedOnce)
    }
}
