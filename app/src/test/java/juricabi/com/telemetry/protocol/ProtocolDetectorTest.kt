package juricabi.com.telemetry.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolDetectorTest {
    @Test
    fun aProtocolIsAnnouncedExactlyOnce() {
        var announcements = 0
        var detected: Protocol? = null
        val detector = ProtocolDetector(object : ProtocolDetector.Callback {
            override fun onProtocolDetected(protocol: Protocol?) {
                announcements++
                detected = protocol
            }
        })
        val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("ltm.log"))
            .use { it.readBytes() }
        for (offset in bytes.indices step 18) {
            var checksum = 0
            for (i in 3..16) checksum = checksum xor (bytes[offset + i].toInt() and 0xFF)
            bytes[offset + 17] = checksum.toByte()
        }

        bytes.forEach { detector.feedData(it.toInt() and 0xFF) }
        repeat(16) { detector.feedData(0) }

        assertTrue("detected ${detected?.javaClass?.simpleName}", detected is LTMProtocol)
        assertEquals(1, announcements)
    }

    @Test
    fun sportIsNotPreemptedByAnotherParser() {
        var detected: Protocol? = null
        val detector = ProtocolDetector(object : ProtocolDetector.Callback {
            override fun onProtocolDetected(protocol: Protocol?) {
                detected = protocol
            }
        })
        val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("sport.log"))
            .use { it.readBytes() }
        for (offset in bytes.indices step 10) {
            var checksum = 0
            for (i in 2..8) checksum += bytes[offset + i].toInt() and 0xFF
            while (checksum > 0xFF) checksum = (checksum and 0xFF) + (checksum ushr 8)
            bytes[offset + 9] = (0xFF - checksum).toByte()
        }

        bytes.forEach { detector.feedData(it.toInt() and 0xFF) }

        assertTrue("detected ${detected?.javaClass?.simpleName}", detected is FrSkySportProtocol)
    }
}
