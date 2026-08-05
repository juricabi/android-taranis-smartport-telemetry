package juricabi.com.telemetry.api

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Fr24ClientTest {

    @Test
    fun malformedUnsignedFrameLengthIsRejected() {
        // gRPC-web lengths are unsigned. 0xfffffff6 must not become -10 and
        // move the parser backwards before the beginning of the response.
        val malformed = byteArrayOf(
            0x80.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xf6.toByte()
        )

        assertNull(Fr24Client().decodeResponse(malformed))
    }

    @Test
    fun validEmptyDataFrameIsNotARequestFailure() {
        val emptyDataFrame = byteArrayOf(0, 0, 0, 0, 0)

        val flights = Fr24Client().decodeResponse(emptyDataFrame)

        assertNotNull(flights)
        assertTrue(flights!!.isEmpty())
    }
}
