package juricabi.com.telemetry.manager

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric: PreferenceManager wraps SharedPreferences and reads a few colour
 * resources, so it needs a real Android context. The defaults matter — they are
 * the behaviour someone gets before they have touched a single setting, and the
 * reconnect/autostart/camera defaults are load-bearing for the rest of the app.
 */
@RunWith(RobolectricTestRunner::class)
class PreferenceManagerTest {

    private fun fresh() = PreferenceManager(ApplicationProvider.getApplicationContext())

    @Test
    fun cameraDefaultsAreFollowOnChaseOff() {
        val pm = fresh()
        assertTrue("a flight is worth keeping in view", pm.getCameraFollow())
        assertFalse("chase is the one you turn on", pm.getCameraChase())
    }

    @Test
    fun reconnectAndAutostartAndLoggingDefaultOn() {
        val pm = fresh()
        assertTrue(pm.getReconnectionEnabled())
        assertTrue(pm.getNetworkReconnectionEnabled())
        assertTrue(pm.getPlaybackAutostart())
        assertTrue(pm.isLoggingEnabled())
        assertTrue(pm.isCSVLoggingEnabled())
    }

    @Test
    fun cameraModeRoundTripsThroughStorage() {
        fresh().setCameraChase(true)
        fresh().setCameraFollow(false)
        // a second instance reads what the first stored
        val pm = fresh()
        assertTrue(pm.getCameraChase())
        assertFalse(pm.getCameraFollow())
    }

    @Test
    fun theOperatorLineInheritsTheOldHomeLineAnswerOnFirstRun() {
        // First run writes show_operator_line from show_home_line (default true)
        // so both read the same stored answer rather than the checkbox guessing.
        val pm = fresh()
        assertTrue(pm.isOperatorLineEnabled())
    }

    @Test
    fun crsfSystemOverrideRoundTripsAndAutoClears() {
        assertEquals(null, fresh().getCrsfSystemOverride())
        fresh().setCrsfSystemOverride("XF")
        assertEquals("XF", fresh().getCrsfSystemOverride())
        // Auto is the absence of an override, not a fourth value.
        fresh().setCrsfSystemOverride(null)
        assertEquals(null, fresh().getCrsfSystemOverride())
    }
}
