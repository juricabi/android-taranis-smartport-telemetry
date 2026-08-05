package juricabi.com.telemetry.protocol.pollers

import java.io.IOException
import java.io.OutputStream

internal class BestEffortLog(stream: OutputStream?) {
    private var stream: OutputStream? = stream

    @Synchronized
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        val current = stream ?: return
        try {
            current.write(bytes, offset, length)
        } catch (_: IOException) {
            retire(current)
        }
    }

    @Synchronized
    fun close() {
        val current = stream ?: return
        retire(current)
    }

    private fun retire(current: OutputStream) {
        stream = null
        try {
            current.close()
        } catch (_: IOException) {
        }
    }
}
