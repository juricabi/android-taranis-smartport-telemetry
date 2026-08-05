package juricabi.com.telemetry.logger

import java.io.IOException
import java.io.Writer

/** A failed companion CSV must never take the telemetry connection with it. */
internal class BestEffortCsvWriter(writer: Writer?) {
    private var writer: Writer? = writer

    @Synchronized
    fun writeLine(line: String): Boolean {
        val current = writer ?: return false
        return try {
            current.append(line)
            current.append('\n')
            true
        } catch (_: IOException) {
            retire(current)
            false
        }
    }

    @Synchronized
    fun isOpen(): Boolean = writer != null

    @Synchronized
    fun close() {
        val current = writer ?: return
        retire(current)
    }

    private fun retire(current: Writer) {
        writer = null
        try {
            current.close()
        } catch (_: IOException) {
        }
    }
}
