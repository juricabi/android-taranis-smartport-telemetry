package juricabi.com.telemetry.logger

import java.io.File
import java.io.FileOutputStream

/**
 * The recording, counting itself as it is written.
 *
 * A log is a recording of the bytes off the link with no clock in it, and the
 * CSV beside it is written on a timer with the clock on every row. Neither says
 * how the two line up — so a replay could only assume the link talked at a
 * steady rate, and a link that went quiet for a minute had that minute spread
 * thinly across the whole flight instead of standing still where it happened.
 *
 * How many bytes had been written when a row was written is the thing that
 * lines them up, and it costs a counter.
 */
class CountingLog(file: File, append: Boolean = false) : FileOutputStream(file, append) {

    /**
     * On append the count carries on from what the file already holds, so the
     * CSV's byte offsets stay true across a reconnect that continues the same
     * recording rather than starting a second one beside it.
     */
    @Volatile
    var bytesWritten = if (append) file.length() else 0L
        private set

    override fun write(b: Int) {
        super.write(b)
        bytesWritten++
    }

    override fun write(b: ByteArray) {
        super.write(b)
        bytesWritten += b.size
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        super.write(b, off, len)
        bytesWritten += len
    }
}
