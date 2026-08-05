package juricabi.com.telemetry.protocol.pollers

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import juricabi.com.telemetry.protocol.*
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UsbDataPoller(
    private val listener: DataDecoder.Listener,
    private val serialPort: UsbSerialPort,
    private val baudrate: Int,
    private val connection: UsbDeviceConnection,
    logFile: FileOutputStream?
) : DataPoller {
    private val log = BestEffortLog(logFile)
    private var outputManager: SerialInputOutputManager? = null
    private var selectedProtocol: Protocol? = null
    private var connectedOnce = false
    private val finished = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    init {
        try {
            serialPort.open(connection)
            serialPort.setParameters(
                baudrate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            connectedOnce = true
            listener.onConnected()

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

            outputManager =
                SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
                    override fun onRunError(e: Exception?) {
                        finish(connectionFailed = !connectedOnce)
                    }

                    override fun onNewData(data: ByteArray?) {
                        if (data == null || finished.get()) return
                        log.write(data)
                        for (byte in data) {
                            if (finished.get()) return
                            listener.onTelemetryByte()
                            if (selectedProtocol != null) {
                                selectedProtocol?.process(byte.toUByte().toInt())
                            } else {
                                protocolDetector.feedData(byte.toUByte().toInt())
                            }
                        }
                        if (!finished.get()) {
                            listener.commit()
                        }
                    }
                })
            executor.submit(outputManager!!)
        } catch (e: Exception) {
            finish(connectionFailed = true)
        }
    }

    private fun finish(connectionFailed: Boolean) {
        if (!finished.compareAndSet(false, true)) return

        outputManager?.stop()
        executor.shutdownNow()
        log.close()
        try {
            serialPort.close()
        } catch (_: IOException) {
        }
        connection.close()

        if (connectionFailed) {
            listener.onConnectionFailed()
        } else {
            listener.onDisconnected()
        }
    }

    override fun disconnect() {
        finish(connectionFailed = !connectedOnce)
    }
}
