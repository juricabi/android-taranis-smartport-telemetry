package juricabi.com.telemetry.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightAltitudeTest {

    @Test
    fun gpsIsPreferredWhileFallbackIsKeptReady() {
        var now = 1L
        val altitude = FlightAltitude { now }
        altitude.onFallback(20f)
        altitude.onGps(120f)

        assertEquals(120f, altitude.forFix(), 0f)

        now += 10_000_000_001L
        altitude.onFallback(30f)
        assertEquals(30f, altitude.forFix(), 0f)
    }

    @Test
    fun replayFixesExpireAHeightEvenWhenWallTimeDoesNotMove() {
        val altitude = FlightAltitude { 1L }
        altitude.onGps(120f)

        assertTrue(altitude.forFix(101).isNaN())
    }

    @Test
    fun resetDoesNotCarryHeightIntoTheNextFlight() {
        val altitude = FlightAltitude { 1L }
        altitude.onFallback(20f)
        altitude.clear()

        assertTrue(altitude.forFix().isNaN())
    }
}
