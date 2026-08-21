package juricabi.com.telemetry.protocol.pollers

import android.bluetooth.BluetoothSocket
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import java.io.FileOutputStream
import java.io.IOException

class BluetoothDataPoller(
    private val bluetoothSocket: BluetoothSocket,
    listener: DataDecoder.Listener,
    outputStream: FileOutputStream?
) : DataPoller {

    private lateinit var thread: Thread

    private val chassis = PollerChassis(listener, outputStream) {
        thread.interrupt()
        try {
            bluetoothSocket.close()
        } catch (_: IOException) {
        }
    }

    init {
        thread = Thread(Runnable {
            try {
                bluetoothSocket.connect()
                if (!bluetoothSocket.isConnected) {
                    chassis.finish()
                    return@Runnable
                }
                chassis.connected()

                val buffer = ByteArray(1024)
                while (!chassis.done) {
                    val size = bluetoothSocket.inputStream.read(buffer)
                    if (size == -1) {
                        chassis.finish()
                        return@Runnable
                    }
                    if (size == 0) continue
                    chassis.feed(buffer, 0, size)
                }
            } catch (e: Exception) {
                chassis.finish()
            }
        })

        thread.start()
    }

    override fun disconnect() {
        chassis.finish()
    }
}
