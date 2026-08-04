package juricabi.com.telemetry.protocol.pollers

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import juricabi.com.telemetry.protocol.*
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

class UsbDataPoller(
    private val listener: DataDecoder.Listener,
    private val serialPort: UsbSerialPort,
    private val baudrate: Int,
    private val connection: UsbDeviceConnection,
    private val logFile: FileOutputStream?
) : DataPoller {
    private var outputManager: SerialInputOutputManager? = null
    private var selectedProtocol: Protocol? = null
    private var connectedOnce = false
    private var isDisconnected = true

    init {
        connectedOnce = false
        try {
            serialPort.open(connection)
            connectedOnce = true;
            isDisconnected = false;
        } catch (e: IOException) {
            listener.onConnectionFailed()
            logFile?.close()
        }

        serialPort.setParameters(
            baudrate,
            8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE
        )

        listener.onConnected()

        val protocolDetector =
            ProtocolDetector(object :
                ProtocolDetector.Callback {
                override fun onProtocolDetected(protocol: Protocol?) {
                    val live = ProtocolFactory.create(protocol, listener)
                    if (live == null) {
                        logFile?.close()
                        listener.onConnectionFailed()
                        outputManager?.stop()
                        return
                    }
                    selectedProtocol = live
                    listener.onProtocolDetected(ProtocolFactory.nameOf(live))
                }
            })

        outputManager =
            SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
                override fun onRunError(e: Exception?) {
                    if ( isDisconnected == false )
                    {
                        isDisconnected == true;
                        if (connectedOnce)
                        {
                            listener.onDisconnected()
                        } else {
                            listener.onConnectionFailed()
                        }
                        logFile?.close()
                    }
                }

                override fun onNewData(data: ByteArray?) {
                    data?.let {
                        logFile?.write(data)
                        data.forEach {
                            if (selectedProtocol != null) {
                                listener?.onTelemetryByte();
                                selectedProtocol?.process(it.toUByte().toInt())
                            } else {
                                listener?.onTelemetryByte();
                                protocolDetector.feedData(it.toUByte().toInt())
                            }
                        }
                        listener?.commit();
                    }
                }
            })
        Executors.newSingleThreadExecutor().submit(outputManager!!)
    }


    override fun disconnect() {
        if ( isDisconnected == false )
        {
            isDisconnected = true;
            outputManager?.stop()
            logFile?.close()
            listener.onDisconnected()
        }
    }
}