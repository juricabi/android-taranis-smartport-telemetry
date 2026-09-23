package juricabi.com.telemetry.protocol.pollers

import android.os.Looper
import juricabi.com.telemetry.protocol.crc.CRC8
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.ByteBuffer

/**
 * A seek hands the screen the fix as the flight had it at each point. The
 * second pass re-decodes the frames that changed it, and a decoder judging a
 * frame by state from later in the log — CRSF's extended fix type — once
 * reported the whole walk with the fix it ended on.
 */
@RunWith(RobolectricTestRunner::class)
class LogPlayerSeekTest {

    private class Screen : DataDecoder.Companion.DefaultDecodeListener() {
        val fixes = ArrayList<Boolean>()
        override fun onGPSState(satellites: Int, gpsFix: Boolean) {
            if (fixes.lastOrNull() != gpsFix) fixes.add(gpsFix)
        }
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(type.toByte()) + payload
        val crc = CRC8()
        crc.reset()
        body.forEach { crc.update(it) }
        return byteArrayOf(0xC8.toByte(), (body.size + 1).toByte()) + body + byteArrayOf(crc.value.toByte())
    }

    private fun gps(step: Int): ByteArray = frame(0x02, ByteBuffer.allocate(15)
        .putInt(450000000 + step * 100).putInt(150000000).putShort(0).putShort(0)
        .putShort((1000 + 120).toShort()).put(5).array())

    private fun extended(fixType: Int): ByteArray =
        frame(0x06, ByteBuffer.allocate(20).put(fixType.toByte()).array())

    @Test
    fun aFixLostMidFlightIsLostInTheSeekToo() {
        var step = 0
        val log = listOf(3, 1, 3).flatMap { fixType ->
            List(20) { extended(fixType) + gps(step++) }
        }.reduce(ByteArray::plus)
        val file = File.createTempFile("seek", ".tlm").apply { writeBytes(log); deleteOnExit() }

        val screen = Screen()
        val player = LogPlayer(screen)
        var size = -1
        player.load(file, object : LogPlayer.DataReadyListener {
            override fun onUpdate(percent: Int) {}
            override fun onDataReady(ready: Int) { size = ready }
            override fun onPlaybackPositionChange(prevPosition: Int, nextPosition: Int) {}
            override fun onPlaybackStateChange(isPlaying: Boolean) {}
            override fun getTotalPlaybackDurationSec() = 30
            override fun getPlaybackAutostart() = false
            override fun onProtocolDetected(protocolName: String) {}
        })
        val until = System.currentTimeMillis() + 10_000
        while (size < 0 && System.currentTimeMillis() < until) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        player.seek(size)

        assertEquals(listOf(true, false, true), screen.fixes)
    }
}
