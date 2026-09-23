package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRC8
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * ExpressLRS 4.1 receivers report their VBAT pad twice: in the battery frame
 * (0x08) in tenths of a volt, and in a CELLS frame (0x0E) in millivolts under
 * a voltage-sensor source id. The exact one wins while it arrives — but only
 * as the same reading: a flight controller's pack is never replaced by it.
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

    // ExpressLRS's battery frame truncates the same millivolts: 11987 -> 119

    @Test
    fun theSameReadingInMillivoltsGoesExact() {
        val captor = Captor()
        feed(CrsfProtocol(captor), battery(119) + cells(128, 11987))
        assertEquals(listOf(11.9f, 11.987f), captor.volts)
    }

    @Test
    fun theRoundedFrameStandsAsideWhileMillivoltsArrive() {
        val captor = Captor()
        feed(CrsfProtocol(captor),
            battery(119) + cells(128, 11987) + battery(119) + cells(128, 11985))
        assertEquals(listOf(11.9f, 11.987f, 11.985f), captor.volts)
    }

    @Test
    fun aValueAbove32VoltsIsNotReadAsNegative() {
        val captor = Captor()
        feed(CrsfProtocol(captor), battery(504) + cells(128, 50400))   // 12S full
        assertEquals(listOf(50.4f, 50.4f), captor.volts)
    }

    @Test
    fun aFlightControllersPackIsNotReplacedByAnUnwiredPad() {
        // the receiver stops its own battery frame for the FC's, but keeps
        // sending its pad in millivolts: 0 when nothing is wired to it
        val captor = Captor()
        feed(CrsfProtocol(captor), battery(168) + cells(128, 0) + battery(168))
        assertEquals(listOf(16.8f, 16.8f), captor.volts)
    }

    @Test
    fun aPadOnAnotherBatteryIsNotThePack() {
        val captor = Captor()
        feed(CrsfProtocol(captor), battery(168) + cells(128, 5012) + battery(167))
        assertEquals(listOf(16.8f, 16.7f), captor.volts)
    }

    @Test
    fun millivoltsWithNoBatteryFrameToMatchSayNothing() {
        val captor = Captor()
        feed(CrsfProtocol(captor), cells(128, 11987))
        assertEquals(emptyList<Float>(), captor.volts)
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
        feed(CrsfProtocol(captor), cells(0, 4012, 4015, 4011, 4013) + battery(160))
        assertEquals(listOf(16.0f), captor.volts)
    }
}
