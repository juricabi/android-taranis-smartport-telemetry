package juricabi.com.telemetry.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The greying-out of a sensor: after a window of silence it counts as gone, and
 * a frame arriving resets it. The 400ms beat is normally a Timer; the test
 * drives tick() by hand so the window is deterministic instead of wall-clock
 * slow. Window set to 1000ms — three beats (1200ms) cross it, two (800ms) do not.
 */
class SensorTimeoutManagerTest {

    private val timedOut = mutableSetOf<Int>()
    private val listener = object : SensorTimeoutManager.Listener {
        override fun onSensorTimeout(sensorId: Int) { timedOut += sensorId }
        override fun onSensorData(sensorId: Int) {}
        override fun onTelemetryRate(rate: Int) {}
    }

    private fun manager() = SensorTimeoutManager(listener).also { it.setTimeoutWindow(1000) }

    private val GPS = SensorTimeoutManager.SENSOR_GPS

    @Test
    fun aSensorIsNotGoneBeforeTheWindowElapses() {
        val m = manager()
        m.tick(); m.tick()                 // 800 of 1000
        assertFalse(m.getSensorTimeout(GPS))
        assertFalse(GPS in timedOut)
    }

    @Test
    fun aSensorGoesOnceTheWindowElapses() {
        val m = manager()
        m.tick(); m.tick(); m.tick()       // 1200 >= 1000
        assertTrue(m.getSensorTimeout(GPS))
        assertTrue(GPS in timedOut)
    }

    @Test
    fun aFrameResetsTheSensorAndKeepsItAlive() {
        val m = manager()
        m.tick(); m.tick()                 // 800
        m.onGPSData(1.0, 2.0)              // a fix arrives → GPS reset to 0
        m.tick(); m.tick()                 // 800 again, still under 1000
        assertFalse(m.getSensorTimeout(GPS))
    }

    @Test
    fun disabledTimeoutsNeverGoGrey() {
        val m = manager()
        m.disableTimeouts()
        m.tick(); m.tick(); m.tick()       // well past the window
        assertFalse(m.getSensorTimeout(GPS))
        assertFalse(GPS in timedOut)
    }
}
