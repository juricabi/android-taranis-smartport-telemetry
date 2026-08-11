package juricabi.com.telemetry.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric: DataService is a Service, so it needs an Android runtime to be
 * built at all — but onCreate is never run here, because the log-session name
 * needs no context, so none of the location/notification machinery has to stand
 * up.
 *
 * beginLogSession is the heart of "one flight, one log": a fresh connect starts
 * a new session (a new name, new files), a reconnect keeps the name a drop left
 * standing so the recording and CSV re-open for append. Its return says whether
 * this connection appends to what the last one left, and that is the contract
 * these pin down — through the return alone, never the timestamped name, which
 * two calls in the same second would share.
 */
@RunWith(RobolectricTestRunner::class)
class DataServiceLogSessionTest {

    private fun service() = Robolectric.buildService(DataService::class.java).get()

    @Test
    fun aFreshConnectStartsANewSessionAndDoesNotAppend() {
        assertFalse(service().beginLogSession(newSession = true))
    }

    @Test
    fun aReconnectAppendsToTheNameTheDropLeftStanding() {
        val s = service()
        assertFalse(s.beginLogSession(newSession = true))    // the flight begins
        assertTrue(s.beginLogSession(newSession = false))    // dropped, back — appends
        assertTrue(s.beginLogSession(newSession = false))    // and again, still one log
    }

    @Test
    fun aFreshConnectAfterAReconnectStartsOver() {
        val s = service()
        assertFalse(s.beginLogSession(newSession = true))
        assertTrue(s.beginLogSession(newSession = false))
        assertFalse(s.beginLogSession(newSession = true))    // a new flight: new name, no append
        assertTrue(s.beginLogSession(newSession = false))    // its own reconnect appends
    }

    @Test
    fun aReconnectWithNoPriorFlightStartsFreshRatherThanAppendToNothing() {
        // A reconnect can never be the very first session in normal use, but if
        // it were, there is no standing name to continue — so it starts a file
        // like a fresh connect instead of appending blind.
        assertFalse(service().beginLogSession(newSession = false))
    }
}
