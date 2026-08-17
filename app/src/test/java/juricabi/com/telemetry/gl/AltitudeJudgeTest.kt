package juricabi.com.telemetry.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the height question, on the bench: what the flight's
 * burials — ground minus reported, per point — prove about where it sits.
 * The terrain-reading walk stays untested here; these weigh its findings.
 */
class AltitudeJudgeTest {

    private fun burials(vararg values: Float) = floatArrayOf(*values)

    @Test
    fun padStartedFlightLiftsByTheLaunchGround() {
        // thirty points standing on a 500 m launch, seventy flying high
        val b = FloatArray(100)
        for (i in 0 until 30) b[i] = 500f
        for (i in 30 until 100) b[i] = 100f + (i - 30)
        val judged = TerrainScene.judge(0f, 500f, b, b.size)
        assertTrue(judged.aboveLaunch)
        // the deepest burial is the launch ground, the answer this always gave
        assertEquals(500f, judged.lift, 1e-3f)
    }

    @Test
    fun midAirStartLiftsByTheDeepestBurialSoFar() {
        // a reconnect to a model already flying: no pad points at all, the
        // burials only say how low the flight has provably sat
        val b = FloatArray(100) { (it + 1) * 10f }
        val judged = TerrainScene.judge(-400f, 900f, b, b.size)
        assertTrue(judged.aboveLaunch)
        // the second-deepest burial: honoured as proof, unswayable alone
        assertEquals(990f, judged.lift, 1e-3f)
    }

    @Test
    fun oneStrayDeepSampleDoesNotSetTheLift() {
        val b = FloatArray(41) { 500f }
        b[40] = 900f // one wild reading, terrain seam or warm-up fix
        val judged = TerrainScene.judge(0f, 500f, b, b.size)
        assertTrue(judged.aboveLaunch)
        assertEquals(500f, judged.lift, 1e-3f)
    }

    @Test
    fun aFlightOfTwoPointsStaysConservative() {
        val judged = TerrainScene.judge(0f, 500f, burials(100f, 400f), 2)
        assertTrue(judged.aboveLaunch)
        // never the single deepest reading
        assertEquals(100f, judged.lift, 1e-3f)
    }

    @Test
    fun aSingleReportCannotSetTheLift() {
        // one reading, however deep, is not proof of where a flight sits —
        // the ratchet would have kept its mistake for the whole flight
        val judged = TerrainScene.judge(-800f, 900f, burials(1700f), 1)
        assertTrue(judged.aboveLaunch)
        assertEquals(0f, judged.lift, 1e-3f)
    }

    @Test
    fun aLiftIsNeverNegative() {
        // the verdict came from points whose ground has not loaded yet;
        // the loaded ones all fly high — no proof is no lift, not a push
        // below what the link reported
        val judged = TerrainScene.judge(-400f, 900f, burials(-120f, -80f, -50f), 3)
        assertTrue(judged.aboveLaunch)
        assertEquals(0f, judged.lift, 1e-3f)
    }

    @Test
    fun seaLevelHeightsNeedNoLift() {
        val judged = TerrainScene.judge(480f, 500f, burials(20f, 10f, 5f), 3)
        assertFalse(judged.aboveLaunch)
        assertEquals(0f, judged.lift, 1e-3f)
    }

    @Test
    fun thirtyMetresOfSlackSeparatesTheVerdicts() {
        // exactly thirty under the lowest ground is still the sea-level side
        assertFalse(TerrainScene.judge(470f, 500f, burials(30f), 1).aboveLaunch)
        assertTrue(TerrainScene.judge(469f, 500f, burials(31f), 1).aboveLaunch)
    }
}
