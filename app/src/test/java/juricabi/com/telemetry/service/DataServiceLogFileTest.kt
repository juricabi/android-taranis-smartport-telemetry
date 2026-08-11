package juricabi.com.telemetry.service

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import juricabi.com.telemetry.logger.CountingLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric: createLogFile is the gate on the .tlm — it opens a recording only
 * when logging is on and storage is granted, and appends onto a file that
 * already stands. onCreate IS run here (unlike the log-session test) because
 * createLogFile needs the PreferenceManager onCreate sets up.
 */
@RunWith(RobolectricTestRunner::class)
class DataServiceLogFileTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun setLogging(on: Boolean) =
        app.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("logging_enabled", on).commit()

    private fun service() = Robolectric.buildService(DataService::class.java).create().get()

    @Before
    fun grantAndEnable() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        setLogging(true)
    }

    @Test
    fun opensARecordingWhenLoggingIsOnAndStorageGranted() {
        val s = service()
        s.beginLogSession(newSession = true)          // mints a name
        val log = s.createLogFile(append = false)
        assertNotNull(log)
        assertTrue(s.isRecording())
        log?.close()
    }

    @Test
    fun opensNothingWhenLoggingIsOff() {
        setLogging(false)
        val s = service()
        s.beginLogSession(newSession = true)
        assertNull(s.createLogFile(append = false))
        assertFalse(s.isRecording())
    }

    @Test
    fun opensNothingWhenStorageIsDenied() {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val s = service()
        s.beginLogSession(newSession = true)
        assertNull(s.createLogFile(append = false))
        assertFalse(s.isRecording())
    }

    @Test
    fun appendContinuesTheSameTlmAcrossAReconnect() {
        val s = service()
        s.beginLogSession(newSession = true)
        val first = s.createLogFile(append = false) as CountingLog
        first.write(ByteArray(40))
        first.close()

        s.beginLogSession(newSession = false)         // a reconnect keeps the name
        val second = s.createLogFile(append = true) as CountingLog
        assertTrue(second.bytesWritten >= 40L)        // continues, not reset to 0
        second.close()
    }
}
