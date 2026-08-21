package juricabi.com.telemetry.protocol.decoder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two structural relays the telemetry bus is made of. Before these, every
 * relay was hand-copied — and a hand-copied relay is where a reading silently
 * stopped.
 */
class BusListenersTest {

    private class Ear : DataDecoder.Companion.DefaultDecodeListener() {
        val heard = mutableListOf<String>()
        override fun onConnected() { heard.add("connected") }
        override fun onFuelData(fuel: Int) { heard.add("fuel:$fuel") }
        override fun onGPSData(latitude: Double, longitude: Double) { heard.add("gps") }
        override fun commit() { heard.add("commit") }
    }

    @Test
    fun forwardingHandsEverythingOn() {
        val ear = Ear()
        val relay = ForwardingListener(ear)
        relay.onConnected()
        relay.onFuelData(42)
        relay.onGPSData(45.0, 15.0)
        relay.commit()
        assertEquals(listOf("connected", "fuel:42", "gps", "commit"), ear.heard)
    }

    @Test
    fun aGuardOverridingRelayHoldsTheWholeStream() {
        val ear = Ear()
        var current = true
        val guard = object : ForwardingListener(ear) {
            override fun relay(deliver: (DataDecoder.Listener) -> Unit) {
                if (current) super.relay(deliver)
            }
        }
        guard.onFuelData(1)
        current = false
        // a retired generation's last words touch nothing, whatever they are
        guard.onFuelData(2)
        guard.onConnected()
        guard.commit()
        assertEquals(listOf("fuel:1"), ear.heard)
    }

    @Test
    fun multicastReadsItsEarsLive() {
        val first = Ear()
        var second: DataDecoder.Listener? = null
        val bus = MulticastListener({ first }, { second })
        // nobody in the second seat yet — delivery must not mind
        bus.onConnected()
        val late = Ear()
        second = late
        bus.commit()
        assertEquals(listOf("connected", "commit"), first.heard)
        assertEquals("a late ear hears from its arrival on", listOf("commit"), late.heard)
    }
}
