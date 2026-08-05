package juricabi.com.telemetry.api

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

        assertTrue(Fr24Client().decodeResponse(malformed).isEmpty())
    }
}
