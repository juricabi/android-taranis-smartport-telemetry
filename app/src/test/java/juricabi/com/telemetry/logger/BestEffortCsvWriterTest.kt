package juricabi.com.telemetry.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.io.Writer

class BestEffortCsvWriterTest {
    @Test
    fun failedWriteIsRetiredWithoutEscaping() {
        val writer = FailingWriter(failWrite = true)
        val csv = BestEffortCsvWriter(writer)

        assertFalse(csv.writeLine("first"))
        assertFalse(csv.writeLine("second"))

        assertEquals(1, writer.writeCalls)
        assertEquals(1, writer.closeCalls)
    }

    @Test
    fun closeFailureIsIgnoredAndCloseIsIdempotent() {
        val writer = FailingWriter(failClose = true)
        val csv = BestEffortCsvWriter(writer)

        csv.close()
        csv.close()

        assertEquals(1, writer.closeCalls)
    }

    private class FailingWriter(
        private val failWrite: Boolean = false,
        private val failClose: Boolean = false
    ) : Writer() {
        var writeCalls = 0
        var closeCalls = 0

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            writeCalls++
            if (failWrite) throw IOException("full")
        }

        override fun flush() = Unit

        override fun close() {
            closeCalls++
            if (failClose) throw IOException("close failed")
        }
    }
}
