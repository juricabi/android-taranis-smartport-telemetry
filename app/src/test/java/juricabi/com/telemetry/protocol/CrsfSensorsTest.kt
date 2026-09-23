package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.crc.CRC8
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Temperature and RPM, in the shapes the firmwares actually send them: iNav's
 * TEMP (0x0D) and RPM (0x0C) from ESC telemetry, ExpressLRS's one ambient
 * TEMP bridged from ArduPilot, and Betaflight's barometer frame (0x11).
 */
class CrsfSensorsTest {

    private class Captor : DataDecoder.Companion.DefaultDecodeListener() {
        val temperatures = ArrayList<Float>()
        val rpms = ArrayList<Int>()
        override fun onTemperatureData(celsius: Float) { temperatures.add(celsius) }
        override fun onRpmData(rpm: Int) { rpms.add(rpm) }
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(type.toByte()) + payload
        val crc = CRC8()
        crc.reset()
        body.forEach { crc.update(it) }
        return byteArrayOf(0xC8.toByte(), (body.size + 1).toByte()) + body +
            byteArrayOf(crc.value.toByte())
    }

    private fun decode(bytes: ByteArray): Captor {
        val captor = Captor()
        val protocol = CrsfProtocol(captor)
        bytes.forEach { protocol.process(it.toInt() and 0xFF) }
        return captor
    }

    private fun temps(sourceId: Int, vararg decidegrees: Int): ByteArray {
        val b = ByteBuffer.allocate(1 + 2 * decidegrees.size)
        b.put(sourceId.toByte())
        decidegrees.forEach { b.putShort(it.toShort()) }
        return frame(0x0D, b.array())
    }

    private fun rpms(vararg values: Int): ByteArray {
        val b = ByteBuffer.allocate(1 + 3 * values.size)
        b.put(0)
        values.forEach { b.put((it shr 16).toByte()); b.put((it shr 8).toByte()); b.put(it.toByte()) }
        return frame(0x0C, b.array())
    }

    @Test
    fun theHottestEscIsTheTemperature() {
        assertEquals(listOf(71.5f), decode(temps(0, 452, 715, 460, 448)).temperatures)
    }

    @Test
    fun aStaleEscIsNotTheColdestReading() {
        assertEquals(listOf(-5.0f), decode(temps(0, -1250, -50)).temperatures)
    }

    @Test
    fun allEscsStaleSaysNothing() {
        assertEquals(emptyList<Float>(), decode(temps(0, -1250, -1250)).temperatures)
    }

    @Test
    fun ardupilotsAmbientThroughExpressLrs() {
        assertEquals(listOf(23.4f), decode(temps(1, 234)).temperatures)
    }

    @Test
    fun betaflightsBarometerTemperature() {
        val b = ByteBuffer.allocate(8)
        b.putInt(101325)   // pascals
        b.putInt(3150)     // hundredths of a degree
        assertEquals(listOf(31.5f), decode(frame(0x11, b.array())).temperatures)
    }

    @Test
    fun theMotorsMeanRpm() {
        assertEquals(listOf(21000), decode(rpms(20000, 22000, 21000, 21000)).rpms)
    }

    @Test
    fun reverseSpinCountsAsSpeed() {
        // turtle mode: two motors reversed
        assertEquals(listOf(8000), decode(rpms(8000, -8000, 8000, -8000)).rpms)
    }

    @Test
    fun aStoppedMotorPullsTheMeanDown() {
        assertEquals(listOf(15000), decode(rpms(20000, 20000, 20000, 0)).rpms)
    }
}
