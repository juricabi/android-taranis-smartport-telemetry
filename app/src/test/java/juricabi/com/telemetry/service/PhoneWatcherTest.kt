package juricabi.com.telemetry.service

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import juricabi.com.telemetry.manager.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric: the watcher through its own interface — fixes arrive on the
 * providers, and what it believes comes out of onFix. The arbitration rules
 * (worthBelieving) used to be testable only by rotating a phone in the field.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneWatcherTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val heard = mutableListOf<Location>()
    private var refuse: (Location) -> Boolean = { false }

    @Before
    fun allowLocation() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        heard.clear()
    }

    private fun watcher(): PhoneWatcher {
        val w = PhoneWatcher(
            app, PreferenceManager(app),
            refuseFix = { refuse(it) },
            onFix = { heard.add(it) },
            onHeading = {}
        )
        w.refresh(linkUp = true)
        return w
    }

    private fun fix(provider: String, lat: Double, accuracy: Float?, ageMs: Long = 0): Location {
        val l = Location(provider)
        l.latitude = lat
        l.longitude = 15.0
        l.time = System.currentTimeMillis() - ageMs
        // delivery throttling reads the elapsed clock, not the wall one
        l.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000
        if (accuracy != null) l.accuracy = accuracy
        return l
    }

    private fun deliver(location: Location) {
        shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
            .simulateLocation(location)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aMastAnswerDoesNotReplaceASatelliteOne() {
        watcher()
        // times are spaced out because delivery itself honours the 1s minTime
        deliver(fix(LocationManager.GPS_PROVIDER, 45.0, accuracy = 5f, ageMs = 5000))
        // the mast: instant, coarse, and hundreds of metres out
        deliver(fix(LocationManager.NETWORK_PROVIDER, 44.0, accuracy = 500f))
        assertEquals(listOf(45.0), heard.map { it.latitude })
        // but the same provider may always update its own answer
        deliver(fix(LocationManager.GPS_PROVIDER, 45.1, accuracy = 8f))
        assertEquals(listOf(45.0, 45.1), heard.map { it.latitude })
    }

    @Test
    fun aStaleFixLosesToAnything() {
        watcher()
        deliver(fix(LocationManager.GPS_PROVIDER, 45.0, accuracy = 5f, ageMs = 25000))
        deliver(fix(LocationManager.NETWORK_PROVIDER, 44.0, accuracy = 500f))
        assertEquals(listOf(45.0, 44.0), heard.map { it.latitude })
    }

    @Test
    fun aRefusedFixNeverEntersArbitration() {
        refuse = { it.latitude == 45.5 }
        watcher()
        deliver(fix(LocationManager.GPS_PROVIDER, 45.5, accuracy = 1f))
        // the refused fix was not merely dropped from the output — it must not
        // have become the held one, or this coarser real answer would lose
        deliver(fix(LocationManager.NETWORK_PROVIDER, 44.0, accuracy = 500f))
        assertEquals(listOf(44.0), heard.map { it.latitude })
    }

    @Test
    fun aScreenComingBackGetsTheHeldFixAtOnce() {
        val w = watcher()
        deliver(fix(LocationManager.GPS_PROVIDER, 45.0, accuracy = 5f))
        val replayed = mutableListOf<Location>()
        w.watch({ replayed.add(it) }, null)
        assertTrue(replayed.any { it.latitude == 45.0 })
    }
}
