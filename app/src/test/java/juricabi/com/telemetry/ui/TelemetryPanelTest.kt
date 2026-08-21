package juricabi.com.telemetry.ui

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import juricabi.com.telemetry.R
import juricabi.com.telemetry.manager.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric drives the panel through the decoder-listener seam it lives at.
 * These readouts spent years fused into MapsActivity, where no test could
 * construct them.
 */
@RunWith(RobolectricTestRunner::class)
class TelemetryPanelTest {

    /**
     * The real telemetry bars, without view_map.xml around them: its floating
     * buttons trip a JVM-only appcompat/material class clash a phone never
     * loads. statustext and the mode row are the tiles living there, so they
     * stand in.
     */
    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setTheme(R.style.AppTheme)
            val root = android.widget.LinearLayout(this)
            layoutInflater.inflate(R.layout.top_layout, root)
            layoutInflater.inflate(R.layout.bottom_layout, root)
            root.addView(TextView(this).also { it.id = R.id.statustext })
            root.addView(TextView(this).also { it.id = R.id.mode })
            setContentView(root)
        }
    }

    private fun build(replaying: Boolean = false): Pair<Host, TelemetryPanel> {
        val host = Robolectric.buildActivity(Host::class.java).create().get()
        val panel = TelemetryPanel(
            host, PreferenceManager(ApplicationProvider.getApplicationContext()),
            showDialog = { dialogsShown++ },
            idle = { false },
            replaying = { replaying },
            linkProtocol = { linkProtocol }
        )
        return host to panel
    }

    private var dialogsShown = 0
    private var linkProtocol = ""

    @Test
    fun readingsLandFormattedOnTheirTiles() {
        val (host, panel) = build()
        panel.onGSpeedData(72.4f)
        panel.onDistanceData(1500)
        panel.onProtocolDetected("Mavlink High Latency")
        assertEquals("72 km/h", host.findViewById<TextView>(R.id.speed).text)
        assertEquals("1.50 km", host.findViewById<TextView>(R.id.distance).text)
        assertEquals("Mavlink High Latency", host.findViewById<TextView>(R.id.protocol).text)
    }

    @Test
    fun anAmbiguousPackAsksOnceAndAReplayNever() {
        dialogsShown = 0
        val (host, panel) = build()
        // 21.0V is a full 5S or a half-used 6S — the ambiguous case
        panel.onVBATOrCellData(21.0f)
        assertEquals(1, dialogsShown)
        // until answered, the larger count is in use: the safe way to be wrong
        assertEquals("3.50 V", host.findViewById<TextView>(R.id.cell_voltage).text)
        panel.onVBATOrCellData(21.1f)
        assertEquals("asked once, not per reading", 1, dialogsShown)

        dialogsShown = 0
        val (_, replayPanel) = build(replaying = true)
        replayPanel.onVBATOrCellData(21.0f)
        assertEquals("a replay's battery is history", 0, dialogsShown)
    }

    @Test
    fun rotationCarriesTheCellQuestion() {
        dialogsShown = 0
        val (_, panel) = build()
        panel.onVBATOrCellData(21.0f)
        assertEquals(1, dialogsShown)
        val state = Bundle()
        panel.saveInto(state)
        // the phone turns; an answerless ask must not be asked a second time
        val (_, rebuilt) = build()
        rebuilt.restoreFrom(state)
        rebuilt.onVBATOrCellData(21.1f)
        assertEquals(1, dialogsShown)
    }

    @Test
    fun resetPutsEveryTileBackToItsRestingFace() {
        val (host, panel) = build()
        panel.onGSpeedData(72.4f)
        panel.reset()
        assertEquals("-", host.findViewById<TextView>(R.id.speed).text)
        assertEquals("0", host.findViewById<TextView>(R.id.satellites).text)
        assertEquals("0 m", host.findViewById<TextView>(R.id.traveled_distance).text)
    }
}
