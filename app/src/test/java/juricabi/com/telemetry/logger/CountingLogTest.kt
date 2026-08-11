package juricabi.com.telemetry.logger

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The recording counts its own bytes so the CSV beside it can say how far
 * through the .tlm each row was written. A reconnect continues the same
 * recording rather than starting a second one, so the count has to carry
 * across a re-open for append — that byte-offset continuity is the thing a
 * replay leans on to put link silence back where it happened, and it is what
 * these lock down.
 */
class CountingLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun freshLogCountsFromZeroAcrossEveryWrite() {
        val file = tmp.newFile("flight.tlm")
        CountingLog(file).use { log ->
            assertEquals(0L, log.bytesWritten)
            log.write(0x7E)                          // single byte
            log.write(byteArrayOf(1, 2, 3))          // whole array
            log.write(byteArrayOf(9, 8, 7, 6), 1, 2) // a slice
            assertEquals(1L + 3L + 2L, log.bytesWritten)
        }
        assertEquals(6L, file.length())
    }

    @Test
    fun writeWithOffsetCountsOnlyTheLengthWritten() {
        val file = tmp.newFile("flight.tlm")
        CountingLog(file).use { log ->
            log.write(ByteArray(10), 3, 4)
            assertEquals(4L, log.bytesWritten)
        }
        assertEquals(4L, file.length())
    }

    @Test
    fun appendContinuesFromWhatTheFileAlreadyHolds() {
        val file = tmp.newFile("flight.tlm")
        CountingLog(file).use { it.write(ByteArray(50)) }   // the first link
        assertEquals(50L, file.length())

        CountingLog(file, append = true).use { log ->       // the reconnect
            assertEquals(50L, log.bytesWritten)             // continues, not reset to 0
            log.write(ByteArray(20))
            assertEquals(70L, log.bytesWritten)
        }
        assertEquals(70L, file.length())                    // one continuous file
    }

    @Test
    fun bytesWrittenStaysTrueToTheFileSizeAcrossAppend() {
        val file = tmp.newFile("flight.tlm")
        CountingLog(file).use { it.write(ByteArray(123)) }
        CountingLog(file, append = true).use { log ->
            log.write(ByteArray(77))
            // the count and the file's real size never diverge — the CSV's
            // LogBytes column is only meaningful if this holds
            assertEquals(file.length(), log.bytesWritten)
        }
    }

    @Test
    fun appendOntoAnAbsentFileStartsAtZero() {
        // The "logging was off on the first link, then on for the reconnect"
        // case: append is asked for, but no file was ever opened, so the count
        // must start fresh rather than from a phantom length.
        val file = File(tmp.root, "never-opened.tlm")
        CountingLog(file, append = true).use { log ->
            assertEquals(0L, log.bytesWritten)
            log.write(ByteArray(5))
            assertEquals(5L, log.bytesWritten)
        }
        assertEquals(5L, file.length())
    }
}
