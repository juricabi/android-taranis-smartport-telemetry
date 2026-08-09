package juricabi.com.telemetry.protocol

import juricabi.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names the readout shows.
 *
 * Two rules: every name the app can report has to survive the shortening,
 * and the short ones have to fit the row they share with a dozen other
 * readings.
 */
class ProtocolNameTest {

    /** Every name any road can hand to the screen. */
    private val reported = listOf(
        "FrSky", "CRSF", ProtocolFactory.CRSF_WITH_PASSTHROUGH, "GHST", "LTM",
        "Mavlink v1", "Mavlink v2", "MAVLink High Latency", "Link Test",
        "Unknown"
    )

    @Test
    fun everyReportedNameIsShownAsSomething() {
        for (name in reported) {
            val shown = ProtocolFactory.shortNameOf(name)
            assertTrue("$name shortened to nothing", shown.isNotEmpty())
            assertTrue("$name is too wide for the row: $shown", shown.length <= 9)
        }
    }

    @Test
    fun theLongOnesAreTheOnesThatChange() {
        assertEquals("MAV v1", ProtocolFactory.shortNameOf("Mavlink v1"))
        assertEquals("MAV v2", ProtocolFactory.shortNameOf("Mavlink v2"))
        assertEquals("MAV HL", ProtocolFactory.shortNameOf("MAVLink High Latency"))
        // and the short ones are left exactly as the logs have always had them
        assertEquals("FrSky", ProtocolFactory.shortNameOf("FrSky"))
        assertEquals("CRSF", ProtocolFactory.shortNameOf("CRSF"))
        assertEquals("GHST", ProtocolFactory.shortNameOf("GHST"))
        assertEquals("LTM", ProtocolFactory.shortNameOf("LTM"))
    }

    @Test
    fun passthroughSaysItIsCarriedOverCrsf() {
        // the link is still CRSF, and the readout has to say both
        val shown = ProtocolFactory.shortNameOf(ProtocolFactory.CRSF_WITH_PASSTHROUGH)
        assertTrue("does not name the link it rides on", shown.startsWith("CRSF"))
        assertTrue("does not name the autopilot", shown.contains("AP"))
    }

    @Test
    fun everyLiveProtocolHasAName() {
        val listener = object : DataDecoder.Companion.DefaultDecodeListener() {}
        val live = listOf(
            FrSkySportProtocol(listener),
            CrsfProtocol(listener),
            GhstProtocol(listener),
            LTMProtocol(listener),
            MAVLinkProtocol(listener),
            MAVLink2Protocol(listener),
            LinkTestProtocol(listener)
        )
        for (protocol in live) {
            val name = ProtocolFactory.nameOf(protocol)
            assertTrue(
                protocol.javaClass.simpleName + " reports no name",
                name.isNotEmpty() && name != "Unknown"
            )
            // whatever detection reports, the row can show it
            assertTrue(ProtocolFactory.shortNameOf(name).isNotEmpty())
        }
    }
}
