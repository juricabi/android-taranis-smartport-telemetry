package juricabi.com.telemetry.logger

import android.os.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Robolectric: OtxCsvLogger writes the CSV to external storage, which needs an
 * Android runtime. What matters is the header rule the reconnect-append brought
 * in — a header starts a file and is never repeated mid-file, and an append onto
 * a file a first link never opened still starts it with one.
 */
@RunWith(RobolectricTestRunner::class)
class OtxCsvLoggerTest {

    private val dir: File get() = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
    private fun csv(name: String) = File(dir, "$name.csv")
    private fun headerCount(name: String) = csv(name).readLines().count { it.startsWith("Date,") }

    /** Opens the log (writing the header, or not, in its init), then flushes and closes. */
    private fun openAndClose(name: String, append: Boolean) {
        OtxCsvLogger(name, append).onDisconnected()
    }

    @Test
    fun aFreshLogGetsExactlyOneHeader() {
        val name = "fresh"
        csv(name).delete()
        openAndClose(name, append = false)
        assertTrue(csv(name).exists())
        assertEquals(1, headerCount(name))
    }

    @Test
    fun appendOntoAnExistingLogDoesNotRepeatTheHeader() {
        val name = "reconnect"
        csv(name).delete()
        openAndClose(name, append = false)   // the first link starts the file
        openAndClose(name, append = true)    // a reconnect continues it
        openAndClose(name, append = true)    // and another
        assertEquals(1, headerCount(name))   // still one header — never mid-file
    }

    @Test
    fun appendOntoAFileNoLinkEverOpenedStartsItWithAHeader() {
        // Logging off on the first link, on for the reconnect: append is asked
        // for, but there is no file yet, so it must start one rather than append
        // headerless.
        val name = "toggled-on"
        csv(name).delete()
        openAndClose(name, append = true)
        assertTrue(csv(name).exists())
        assertEquals(1, headerCount(name))
    }
}
