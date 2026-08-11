package juricabi.com.telemetry.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JVM tests — GeoUtils is pure maths with no Android in it. The compass
 * turns and the great-circle distance are used all over the camera and the
 * track, and the turn had already been written eight ways with one sign wrong,
 * so it is worth pinning down.
 */
class GeoUtilsTest {

    @Test
    fun shortestTurnGoesTheShortWayRoundTheCompass() {
        assertEquals(90f, GeoUtils.shortestTurn(0f, 90f), 1e-4f)
        assertEquals(-90f, GeoUtils.shortestTurn(0f, 270f), 1e-4f)   // 270 is -90 the short way
        assertEquals(20f, GeoUtils.shortestTurn(350f, 10f), 1e-4f)   // across north
        assertEquals(-20f, GeoUtils.shortestTurn(10f, 350f), 1e-4f)
        assertEquals(0f, GeoUtils.shortestTurn(123f, 123f), 1e-4f)
    }

    @Test
    fun turnTowardsWalksPartOfTheWay() {
        assertEquals(0f, GeoUtils.turnTowards(0f, 90f, 0f), 1e-4f)
        assertEquals(45f, GeoUtils.turnTowards(0f, 90f, 0.5f), 1e-4f)
        assertEquals(90f, GeoUtils.turnTowards(0f, 90f, 1f), 1e-4f)
        assertEquals(0f, GeoUtils.turnTowards(350f, 10f, 0.5f), 1e-4f) // halfway across north is due north
    }

    @Test
    fun distanceIsZeroForTheSamePoint() {
        assertEquals(0.0, GeoUtils.computeDistanceBetween(45.0, 15.0, 45.0, 15.0), 1e-6)
    }

    @Test
    fun oneDegreeOfLatitudeIsAboutOneHundredElevenKm() {
        val d = GeoUtils.computeDistanceBetween(0.0, 0.0, 1.0, 0.0)
        assertEquals(111195.0, d, 5.0)
    }

    @Test
    fun distanceIsSymmetric() {
        val ab = GeoUtils.computeDistanceBetween(43.68, 170.12, 44.67, 167.93)
        val ba = GeoUtils.computeDistanceBetween(44.67, 167.93, 43.68, 170.12)
        assertEquals(ab, ba, 1e-6)
    }

    @Test
    fun offsetThenDistanceComesBackToTheDistanceAskedFor() {
        // computeOffset and computeDistanceBetween share the same sphere, so a
        // point pushed 5 km out should read 5 km away.
        val (lat, lon) = GeoUtils.computeOffset(43.68, 170.12, 5000.0, 42.0)
        val back = GeoUtils.computeDistanceBetween(43.68, 170.12, lat, lon)
        assertEquals(5000.0, back, 1.0)
    }
}
