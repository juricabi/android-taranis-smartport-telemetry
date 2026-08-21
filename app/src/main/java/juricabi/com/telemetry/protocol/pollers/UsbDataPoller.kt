package juricabi.com.telemetry.protocol.pollers

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class UsbDataPoller(
    listener: DataDecoder.Listener,
    private val serialPort: UsbSerialPort,
    private val baudrate: Int,
    private val connection: UsbDeviceConnection,
    logFile: FileOutputStream?
) : DataPoller {

    private var outputManager: SerialInputOutputManager? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val chassis = PollerChassis(listener, logFile) {
        outputManager?.stop()
        executor.shutdownNow()
        try {
            serialPort.close()
        } catch (_: IOException) {
        }
        connection.close()
    }

    init {
        try {
            serialPort.open(connection)
            serialPort.setParameters(
                baudrate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            chassis.connected()

            outputManager =
                SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
                    override fun onRunError(e: Exception?) {
                        chassis.finish()
                    }

                    override fun onNewData(data: ByteArray?) {
                        if (data == null) return
                        chassis.feed(data)
                    }
                })
            executor.submit(outputManager!!)
        } catch (e: Exception) {
            chassis.finish(connectionFailed = true)
        }
    }

    override fun disconnect() {
        chassis.finish()
    }
}
