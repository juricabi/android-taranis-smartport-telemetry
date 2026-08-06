package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRCMAVLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The one command the app sends: MAV_CMD_CONTROL_HIGH_LATENCY, MAVLink 2. */
class MavCommandsTest {

    @Test
    fun enableFrameIsWellFormed() {
        val frame = MavCommands.controlHighLatency(enable = true, sequence = 3)
        assertEquals("header, payload and checksum", 1 + 9 + 33 + 2, frame.size)
        assertEquals(0xFD.toByte(), frame[0])
        assertEquals("payload length", 33, frame[1].toInt())
        assertEquals("sequence", 3, frame[4].toInt())
        assertEquals("system 255", 0xFF.toByte(), frame[5])
        assertEquals("component 190", 0xBE.toByte(), frame[6])
        assertEquals("COMMAND_LONG", 76, frame[7].toInt())
        assertEquals(0, frame[8].toInt())
        assertEquals(0, frame[9].toInt())

        val payload = ByteBuffer.wrap(frame, 10, 33).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("param1 is one", 1f, payload.float, 0f)
        for (i in 2..7) assertEquals("param$i is nought", 0f, payload.float, 0f)
        assertEquals("MAV_CMD_CONTROL_HIGH_LATENCY", 2600,
            payload.short.toInt() and 0xFFFF)
        assertEquals("broadcast system", 0, payload.get().toInt())
        assertEquals("broadcast component", 0, payload.get().toInt())
        assertEquals("no confirmation", 0, payload.get().toInt())
    }

    @Test
    fun matchesTheIndependentReferenceByteForByte() {
        // computed by an X.25 implementation that shares no code with
        // CRCMAVLink — the one in tools/simflight.py, CRC_EXTRA 152 — so the
        // frame the app sends is the frame the simulator and an autopilot
        // will accept
        val reference = "fd21000003ffbe4c00000000803f0000000000000000000000" +
            "00000000000000000000000000280a000000ce2d"
        val frame = MavCommands.controlHighLatency(enable = true, sequence = 3)
        assertEquals(reference, frame.joinToString("") {
            String.format("%02x", it.toInt() and 0xFF)
        })
    }

    @Test
    fun checksumValidatesAgainstTheTable() {
        val frame = MavCommands.controlHighLatency(enable = true, sequence = 0)
        val crc = CRCMAVLink()
        crc.start_checksum()
        for (i in 1 until frame.size - 2) {
            crc.update_checksum(frame[i].toInt() and 0xFF)
        }
        crc.finish_checksum(76)
        assertEquals(crc.lsb.toByte(), frame[frame.size - 2])
        assertEquals(crc.msb.toByte(), frame[frame.size - 1])
    }

    @Test
    fun disableDiffersOnlyInParamOne() {
        val on = MavCommands.controlHighLatency(enable = true, sequence = 1)
        val off = MavCommands.controlHighLatency(enable = false, sequence = 1)
        assertEquals(0f, ByteBuffer.wrap(off, 10, 4).order(ByteOrder.LITTLE_ENDIAN).float, 0f)
        assertNotEquals("frames must differ", on.toList(), off.toList())
        // everything between param1 and the checksum is identical
        for (i in 14 until on.size - 2) {
            assertEquals("byte $i", on[i], off[i])
        }
    }
}
