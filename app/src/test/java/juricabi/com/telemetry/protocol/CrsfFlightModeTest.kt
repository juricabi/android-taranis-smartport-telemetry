package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.decoder.CrsfDataDecoder
import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The armed flag as each firmware spells it in the CRSF mode text.
 * Betaflight appends one character while disarmed — '*' ready to arm,
 * '!' arming blocked, '?' GPS rescue unavailable — and none in failsafe,
 * whose name is literally "!FS!". iNav appends nothing and says
 * disarmed in whole words instead. The disarmed-height gate hangs off
 * this flag, so a mark read wrong is a launch spike drawn.
 */
class CrsfFlightModeTest {

    private class Captor : DataDecoder.Companion.DefaultDecodeListener() {
        var armed: Boolean? = null
        var mode: DataDecoder.Companion.FlyMode? = null
        override fun onFlyModeData(
            armed: Boolean, heading: Boolean,
            firstFlightMode: DataDecoder.Companion.FlyMode?,
            secondFlightMode: DataDecoder.Companion.FlyMode?
        ) {
            this.armed = armed
            this.mode = firstFlightMode
        }
    }

    private fun decode(text: String): Captor {
        val captor = Captor()
        val decoder = CrsfDataDecoder(captor)
        val raw = byteArrayOf(0x21) + text.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        decoder.decodeData(Protocol.Companion.TelemetryData(Protocol.FLYMODE, 0, raw))
        return captor
    }

    @Test
    fun aBareModeNameIsArmed() {
        val c = decode("ACRO")
        assertTrue(c.armed!!)
        assertEquals(DataDecoder.Companion.FlyMode.ACRO, c.mode)
    }

    @Test
    fun everyDisarmedMarkIsRead() {
        // only the star was read once, and a bench quad with arming
        // blocked ("ACRO!") was believed armed
        for (said in listOf("ACRO*", "ACRO!", "ACRO?")) {
            val c = decode(said)
            assertFalse(said, c.armed!!)
            assertEquals(said, DataDecoder.Companion.FlyMode.ACRO, c.mode)
        }
    }

    @Test
    fun failsafeIsNotAMarkedName() {
        val c = decode("!FS!")
        assertTrue(c.armed!!)
        assertEquals(DataDecoder.Companion.FlyMode.FAILSAFE, c.mode)
    }

    @Test
    fun inavSaysDisarmedInWholeWords() {
        assertFalse(decode("OK").armed!!)
        assertFalse(decode("WAIT").armed!!)
    }
}
