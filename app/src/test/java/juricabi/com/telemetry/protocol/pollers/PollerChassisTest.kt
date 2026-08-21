package juricabi.com.telemetry.protocol.pollers

import android.os.Looper
import juricabi.com.telemetry.protocol.crc.CRC8
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.nio.ByteBuffer

/**
 * The shared poller road, driven by a scripted byte source: detection, the
 * pump, the commit-per-batch rule, and the single main-thread terminal
 * callback. None of this machinery had a test while it lived as four copies.
 */
@RunWith(RobolectricTestRunner::class)
class PollerChassisTest {

    private class Heard : DataDecoder.Companion.DefaultDecodeListener() {
        val protocols = mutableListOf<String>()
        var commits = 0
        var connected = 0
        var disconnected = 0
        var failed = 0
        var positions = 0
        override fun onProtocolDetected(protocolName: String) { protocols.add(protocolName) }
        override fun commit() { commits++ }
        override fun onConnected() { connected++ }
        override fun onDisconnected() { disconnected++ }
        override fun onConnectionFailed() { failed++ }
        override fun onGPSData(latitude: Double, longitude: Double) { positions++ }
    }

    private fun crsfGpsFrame(): ByteArray {
        val payload = ByteBuffer.allocate(15)
            .putInt(451234567).putInt(161234567)
            .putShort(100).putShort(9000).putShort(1100).put(12)
            .array()
        val body = byteArrayOf(0x02) + payload
        val crc = CRC8()
        body.forEach { crc.update(it) }
        return byteArrayOf(0xc8.toByte(), (body.size + 1).toByte()) + body + crc.value.toByte()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun detectionSelectsNamesAndDecodes() {
        val heard = Heard()
        var closed = 0
        val chassis = PollerChassis(heard, null) { closed++ }
        chassis.connected()

        // two clean decodes are the detection threshold; the third frame
        // arrives after selection and lands in the live protocol
        chassis.feed(crsfGpsFrame())
        chassis.feed(crsfGpsFrame())
        chassis.feed(crsfGpsFrame())

        assertEquals(listOf("CRSF"), heard.protocols)
        assertTrue("decoded positions flow after selection", heard.positions > 0)
        assertEquals("one commit per batch", 3, heard.commits)
        assertEquals(0, closed)
    }

    @Test
    fun exactlyOneTerminalCallbackPostedToMain() {
        val heard = Heard()
        var closed = 0
        val chassis = PollerChassis(heard, null) { closed++ }
        chassis.connected()
        chassis.finish()
        chassis.finish()
        chassis.finish(connectionFailed = true)

        // the callbacks ride the main looper — nothing lands until it runs
        assertEquals(0, heard.disconnected)
        idle()
        assertEquals(1, heard.connected)
        assertEquals("came up, so it disconnects", 1, heard.disconnected)
        assertEquals(0, heard.failed)
        assertEquals("teardown runs exactly once", 1, closed)
    }

    @Test
    fun aLinkThatNeverCameUpFails() {
        val heard = Heard()
        val chassis = PollerChassis(heard, null) {}
        chassis.finish()
        idle()
        assertEquals(1, heard.failed)
        assertEquals(0, heard.disconnected)
    }

    @Test
    fun bytesAfterTheExitAreDropped() {
        val heard = Heard()
        val chassis = PollerChassis(heard, null) {}
        chassis.connected()
        chassis.feed(crsfGpsFrame())
        chassis.feed(crsfGpsFrame())
        chassis.finish()
        val commitsAtExit = heard.commits
        chassis.feed(crsfGpsFrame())
        assertEquals("no decode and no commit after the exit", commitsAtExit, heard.commits)
    }
}
