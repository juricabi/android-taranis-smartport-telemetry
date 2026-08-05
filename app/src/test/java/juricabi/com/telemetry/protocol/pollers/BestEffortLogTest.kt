package juricabi.com.telemetry.protocol.pollers

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.io.OutputStream

class BestEffortLogTest {
    @Test
    fun failedWriteIsRetiredWithoutEscaping() {
        val stream = FailingStream(failWrite = true)
        val log = BestEffortLog(stream)

        log.write(byteArrayOf(1))
        log.write(byteArrayOf(2))

        assertEquals(1, stream.writeCalls)
        assertEquals(1, stream.closeCalls)
    }

    @Test
    fun closeFailureIsIgnoredAndCloseIsIdempotent() {
        val stream = FailingStream(failClose = true)
        val log = BestEffortLog(stream)

        log.close()
        log.close()

        assertEquals(1, stream.closeCalls)
    }

    private class FailingStream(
        private val failWrite: Boolean = false,
        private val failClose: Boolean = false
    ) : OutputStream() {
        var writeCalls = 0
        var closeCalls = 0

        override fun write(value: Int) {
            writeCalls++
            if (failWrite) throw IOException("full")
        }

        override fun close() {
            closeCalls++
            if (failClose) throw IOException("close failed")
        }
    }
}
