package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRC8
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * ExpressLRS 4.1 receivers report their VBAT pad twice: in the battery frame
 * (0x08) in tenths of a volt, and in a CELLS frame (0x0E) in millivolts under
 * a voltage-sensor source id. The exact one wins while it arrives.
 */
class CrsfCellsTest {

    private class Captor : DataDecoder.Companion.DefaultDecodeListener() {
        val volts = ArrayList<Float>()
        override fun onVBATOrCellData(voltage: Float) { volts.add(voltage) }
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(type.toByte()) + payload
        val crc = CRC8()
        crc.reset()
        body.forEach { crc.update(it) }
        return byteArrayOf(0xC8.toByte(), (body.size + 1).toByte()) + body +
            byteArrayOf(crc.value.toByte())
    }

    private fun feed(protocol: Protocol, bytes: ByteArray) {
        bytes.forEach { protocol.process(it.toInt() and 0xFF) }
    }

    private fun cells(sourceId: Int, vararg millivolts: Int): ByteArray {
        val b = ByteBuffer.allocate(1 + 2 * millivolts.size)
        b.put(sourceId.toByte())
        millivolts.forEach { b.putShort(it.toShort()) }
        return frame(0x0E, b.array())
    }

    private fun battery(decivolts: Int): ByteArray {
        val b = ByteBuffer.allocate(8)
        b.putShort(decivolts.toShort())   // voltage, 0.1 V
        b.putShort(0)                     // current
        b.put(byteArrayOf(0, 0, 0))       // capacity used
        b.put(0)                          // remaining
        return frame(0x08, b.array())
    }

    @Test
    fun millivoltsReachTheVoltageExactly() {
        val captor = Captor()
        feed(CrsfProtocol(captor), cells(128, 11987))
        assertEquals(listOf(11.987f), captor.volts)
    }

    @Test
    fun aValueAbove32VoltsIsNotReadAsNegative() {
        val captor = Captor()
        feed(CrsfProtocol(captor), cells(128, 50400))   // 12S full
        assertEquals(listOf(50.4f), captor.volts)
    }

    @Test
    fun theRoundedFrameStandsAsideWhileMillivoltsArrive() {
        val captor = Captor()
        val protocol = CrsfProtocol(captor)
        feed(protocol, cells(128, 11987))
        feed(protocol, battery(120))
        feed(protocol, cells(128, 11985))
        assertEquals(listOf(11.987f, 11.985f), captor.volts)
    }

    @Test
    fun theBatteryFrameAloneStillReports() {
        val captor = Captor()
        feed(CrsfProtocol(captor), battery(168))
        assertEquals(listOf(16.8f), captor.volts)
    }

    @Test
    fun aBatterysOwnCellsAreNotTakenForThePack() {
        val captor = Captor()
        val protocol = CrsfProtocol(captor)
        feed(protocol, cells(0, 4012, 4015, 4011, 4013))
        feed(protocol, battery(160))
        assertEquals(listOf(16.0f), captor.volts)
    }
}
