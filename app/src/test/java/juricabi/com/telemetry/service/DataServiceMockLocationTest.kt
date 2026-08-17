package juricabi.com.telemetry.service

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLocationManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Robolectric emulates the reading side of LocationManager but not the
 * test-provider writing side — those methods fall through to framework code
 * that talks to a system service which is not there, and quietly do nothing.
 * This bridges the four the service calls onto the shadow's own state, so a
 * published fix becomes observable and carries the mock mark the real system
 * would stamp on it.
 */
@Implements(LocationManager::class)
class ShadowTestProviderLocationManager : ShadowLocationManager() {

    companion object {
        // whether this app is picked under Developer options, which is the
        // whole grant the real addTestProvider checks
        var chosen = true
    }

    private val testProviders = mutableSetOf<String>()

    @Implementation
    protected fun addTestProvider(
        name: String, requiresNetwork: Boolean, requiresSatellite: Boolean,
        requiresCell: Boolean, hasMonetaryCost: Boolean, supportsAltitude: Boolean,
        supportsSpeed: Boolean, supportsBearing: Boolean,
        powerRequirement: Int, accuracy: Int
    ) {
        if (!chosen) throw SecurityException("not the chosen mock location app")
        testProviders.add(name)
    }

    @Implementation
    protected fun setTestProviderEnabled(name: String, enabled: Boolean) {
        setProviderEnabled(name, enabled)
    }

    @Implementation
    protected fun setTestProviderLocation(name: String, location: Location) {
        if (name !in testProviders) throw IllegalArgumentException("no test provider $name")
        try {
            ReflectionHelpers.callInstanceMethod<Void>(
                location, "setIsFromMockProvider", ClassParameter.from(Boolean::class.java, true)
            )
        } catch (e: RuntimeException) {
            // the hidden setter was renamed on the way to 34
            ReflectionHelpers.callInstanceMethod<Void>(
                location, "setMock", ClassParameter.from(Boolean::class.java, true)
            )
        }
        simulateLocation(location)
    }

    @Implementation
    protected fun removeTestProvider(name: String) {
        testProviders.remove(name)
    }
}

/**
 * The platform converter needs the system's geoid table, which the test JVM
 * does not carry; a flat 45.2 m gap — the Velebit number — stands in for it,
 * so what the test actually checks is the inversion arithmetic.
 */
@Implements(android.location.altitude.AltitudeConverter::class, minSdk = 34)
class ShadowFlatGeoidConverter {
    @Implementation
    protected fun addMslAltitudeToLocation(context: android.content.Context, location: Location) {
        location.mslAltitudeMeters = location.altitude - 45.2
    }
}

/**
 * Robolectric: the drone's GPS republished as the phone's own position — the
 * mock provider goes up with the link and the setting, each decoded fix lands
 * on the GPS provider enriched with what the link has said, and the phone's
 * real position comes back at disconnect. The last test guards the recording:
 * our own mock must never be believed as where the operator stands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowTestProviderLocationManager::class])
class DataServiceMockLocationTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun chosenAsTheMockApp() {
        // the sandbox outlives a test, and a leaked "not chosen" would fail
        // whichever test runs after the one that set it
        ShadowTestProviderLocationManager.chosen = true
    }

    private fun setMocking(on: Boolean) =
        app.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("mock_location_enabled", on).commit()

    private fun service() = Robolectric.buildService(DataService::class.java).create().get()

    private fun locationManager() =
        app.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun lastGpsFix(): Location? {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        return locationManager().getLastKnownLocation(LocationManager.GPS_PROVIDER)
    }

    @Test
    fun publishesTheDroneFixEnrichedWithWhatTheLinkSaid() {
        setMocking(true)
        val s = service()
        s.onConnected()
        s.onGPSState(10, true)
        s.onGPSAltitudeData(120f)
        s.onGSpeedData(36f)
        s.onHeadingData(90f)
        s.onGPSData(45.5, 15.5)

        val fix = lastGpsFix()
        assertNotNull(fix)
        assertEquals(45.5, fix!!.latitude, 1e-9)
        assertEquals(15.5, fix.longitude, 1e-9)
        assertEquals(120.0, fix.altitude, 1e-6)
        assertEquals(10f, fix.speed, 1e-3f) // 36 km/h, said in m/s
        assertEquals(90f, fix.bearing, 1e-3f)
        assertTrue(fix.isFromMockProvider)
    }

    @Test
    @Config(sdk = [34], shadows = [ShadowFlatGeoidConverter::class])
    fun theSeaLevelAltitudeIsLiftedOntoTheEllipsoid() {
        setMocking(true)
        val s = service()
        s.onConnected()
        s.onGPSState(10, true)
        s.onGPSAltitudeData(120f)
        s.onGPSData(45.5, 15.5)

        val fix = lastGpsFix()
        assertNotNull(fix)
        // the raw field carries true ellipsoidal height: MSL plus the gap
        assertEquals(165.2, fix!!.altitude, 1e-6)
        // and the MSL field carries the drone's own number, underived
        assertTrue(fix.hasMslAltitude())
        assertEquals(120.0, fix.mslAltitudeMeters, 1e-6)
    }

    @Test
    fun publishesNothingBeforeTheDroneHasAFix() {
        setMocking(true)
        val s = service()
        s.onConnected()
        s.onGPSData(45.5, 15.5) // no onGPSState yet: a receiver warming up
        assertNull(lastGpsFix())
    }

    @Test
    fun publishesNothingWhileTheSwitchIsOff() {
        // the default — never written, so this also checks off is the default
        val s = service()
        s.onConnected()
        s.onGPSState(10, true)
        s.onGPSData(45.5, 15.5)
        assertNull(lastGpsFix())
    }

    @Test
    fun thePickUnderDeveloperOptionsTakesEffectWithoutReconnecting() {
        setMocking(true)
        ShadowTestProviderLocationManager.chosen = false
        val s = service()
        s.onConnected()
        s.onGPSState(10, true)
        s.onGPSData(45.5, 15.5)
        // the switch stands on, obeyed but unchosen, publishing nothing
        assertNull(lastGpsFix())

        // the pick lands in Developer options mid-link — where the toast
        // sends people — and nobody reconnects
        ShadowTestProviderLocationManager.chosen = true
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(ops).setMode(
            AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), app.packageName,
            AppOpsManager.MODE_ALLOWED
        )
        shadowOf(Looper.getMainLooper()).idle()

        s.onGPSData(45.6, 15.6)
        val fix = lastGpsFix()
        assertNotNull(fix)
        assertEquals(45.6, fix!!.latitude, 1e-9)
    }

    @Test
    fun theRealPositionComesBackAtDisconnect() {
        setMocking(true)
        val s = service()
        s.onConnected()
        s.onGPSState(10, true)
        s.onGPSData(45.5, 15.5)
        s.onDisconnected()
        s.onGPSData(46.0, 16.0) // a straggler after the link ended
        val fix = lastGpsFix()
        assertNotNull(fix)
        assertEquals(45.5, fix!!.latitude, 1e-9)
    }

    @Test
    fun ownMockFixesAreNotBelievedAsTheOperator() {
        setMocking(true)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val s = service()
        s.onConnected()

        val heard = mutableListOf<Location>()
        s.watchPhone({ heard.add(it) }, null)

        // the drone's mocked fix, arriving back on the phone's GPS provider
        val drone = Location(LocationManager.GPS_PROVIDER)
        drone.latitude = 45.5
        drone.longitude = 15.5
        drone.time = System.currentTimeMillis()
        // the hidden setter the system uses when a test provider is the source
        ReflectionHelpers.callInstanceMethod<Void>(
            drone, "setIsFromMockProvider", ClassParameter.from(Boolean::class.java, true)
        )
        shadowOf(locationManager()).simulateLocation(drone)

        val operator = Location(LocationManager.NETWORK_PROVIDER)
        operator.latitude = 44.0
        operator.longitude = 14.0
        operator.time = System.currentTimeMillis()
        shadowOf(locationManager()).simulateLocation(operator)

        // the deliveries are posted to the main looper, which a test must run
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(heard.none { it.latitude == 45.5 })
        assertTrue(heard.any { it.latitude == 44.0 })
    }
}
