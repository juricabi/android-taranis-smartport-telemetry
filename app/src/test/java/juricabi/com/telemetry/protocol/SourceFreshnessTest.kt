package juricabi.com.telemetry.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A preferred telemetry source is a stream, not a capability found once: the
 * fallback only yields while the preferred side is actually arriving. Freshness
 * runs on an injected clock and also caps the alternate frames, so a replay
 * decoded in a burst — where wall time barely moves — still hands over after
 * enough silence.
 */
class SourceFreshnessTest {

    private var now = 0L
    private fun source() = SourceFreshness { now }

    @Test
    fun aSourceThatNeverArrivedIsNotFresh() {
        assertFalse(source().fresh())
    }

    @Test
    fun freshRightAfterArrival() {
        val s = source()
        s.arrived()
        assertTrue(s.fresh())
    }

    @Test
    fun staleAfterThreeSecondsOfSilence() {
        val s = source()
        s.arrived()
        now += 3_000_000_000L + 1        // just past the timeout
        assertFalse(s.fresh())
    }

    @Test
    fun staleAfterTooManyAlternateFramesEvenWithTimeStandingStill() {
        val s = source()
        s.arrived()
        repeat(60) { assertTrue(s.fresh()) }   // up to the cap stays fresh
        assertFalse(s.fresh())                 // the 61st is stale
    }

    @Test
    fun arrivalRefreshesBothTheClockAndTheFrameCount() {
        val s = source()
        s.arrived()
        repeat(60) { s.fresh() }               // exhaust the cap
        assertFalse(s.fresh())
        s.arrived()                            // a new preferred frame
        assertTrue(s.fresh())                  // fresh again
    }
}
