package juricabi.com.telemetry.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Longitude arithmetic across the 180th meridian.
 *
 * A flight at Fiji straddles it, and taken raw every difference reads as
 * most of the way round the world: the flight's middle landed in the Gulf
 * of Guinea, and the world was built there with nothing under the model.
 * These are the cases that cannot be flown to from here.
 */
class AntimeridianTest {

    private val metre = 1.0 / 111320.0

    @Test
    fun theShortWayRoundIsTheAnswerAtTheMeridian() {
        // twenty kilometres apart, not three hundred and forty degrees
        assertEquals(-0.2, TerrainScene.apart(179.9, -179.9), 1e-9)
        assertEquals(0.2, TerrainScene.apart(-179.9, 179.9), 1e-9)
        // and the ordinary case is untouched
        assertEquals(1.5, TerrainScene.apart(16.5, 15.0), 1e-9)
        assertEquals(-1.5, TerrainScene.apart(15.0, 16.5), 1e-9)
        // the far side of the world stays the far side, either way round
        assertEquals(180.0, Math.abs(TerrainScene.apart(0.0, 180.0)), 1e-9)
    }

    @Test
    fun aLongitudeCarriedPastTheMeridianComesBackInside() {
        assertEquals(-179.5, TerrainScene.wrapped(180.5), 1e-9)
        assertEquals(179.5, TerrainScene.wrapped(-180.5), 1e-9)
        assertEquals(0.0, TerrainScene.wrapped(360.0), 1e-9)
        assertEquals(15.0, TerrainScene.wrapped(15.0), 1e-9)
    }

    @Test
    fun aStraddlingFlightKeepsItsOrderAndItsMiddle() {
        // read on from the first point, the run stays monotonic instead of
        // jumping the width of the world
        val first = 179.9
        val counted = listOf(179.9, 179.95, -179.99, -179.9)
            .map { TerrainScene.unwrapped(it, first) }
        for (i in 1 until counted.size) {
            assertTrue("must keep going east", counted[i] > counted[i - 1])
        }
        val middle = TerrainScene.wrapped((counted.first() + counted.last()) / 2)
        // halfway between 179.9 and -179.9 is the meridian itself, not zero
        assertEquals(180.0, Math.abs(middle), 1e-9)
    }

    @Test
    fun theWorldIsBuiltOnTheFlightAndNotOnTheOtherSideOfTheGlobe() {
        val scene = TerrainScene()
        // Taveuni, Fiji: the line runs through the island
        val flight = listOf(
            TerrainScene.TrackPoint(-16.85, 179.95, 100f),
            TerrainScene.TrackPoint(-16.85, 179.99, 100f),
            TerrainScene.TrackPoint(-16.85, -179.99, 100f),
            TerrainScene.TrackPoint(-16.85, -179.95, 100f)
        )
        assertTrue(scene.setTrack(flight))

        // the origin is on the island, not off West Africa
        assertEquals(-16.85, scene.originLat, 1e-6)
        assertTrue(
            "origin longitude " + scene.originLon + " is not at the meridian",
            Math.abs(TerrainScene.apart(scene.originLon, 180.0)) < 0.1
        )

        // and every point of it stands within a few kilometres of that origin
        for (p in flight) {
            val east = Math.abs(scene.east(p.lon))
            assertTrue("point " + p.lon + " sits " + east + "m away", east < 10_000f)
        }
    }

    @Test
    fun theFrameRoundTripsAcrossTheMeridian() {
        val scene = TerrainScene()
        scene.setOrigin(-16.85, 179.98)
        for (lon in listOf(179.9, 179.99, -179.99, -179.9, 180.0)) {
            val back = scene.lonAt(scene.east(lon))
            assertEquals(
                "round trip of " + lon,
                0.0, TerrainScene.apart(back, lon), 1e-6
            )
            assertTrue("stays a real longitude", back >= -180.0 && back <= 180.0)
        }
    }

    @Test
    fun aFlightThatCrossesTheMeridianIsNotTreatedAsFarAway() {
        val scene = TerrainScene()
        // the world stands just west of the line
        scene.setOrigin(-16.85, 179.98)
        val generation = scene.originGeneration
        // and the flight steps a kilometre over it
        assertTrue(
            scene.setTrack(
                listOf(
                    TerrainScene.TrackPoint(-16.85, 179.99, 100f),
                    TerrainScene.TrackPoint(-16.85, -179.99, 100f)
                )
            )
        )
        assertEquals(
            "a kilometre is not fifty, whichever side of the line it is on",
            generation, scene.originGeneration
        )
    }

    @Test
    fun aFlightGenuinelyFarAwayStillMovesTheWorld() {
        val scene = TerrainScene()
        scene.setOrigin(45.0, 15.0)
        val generation = scene.originGeneration
        assertTrue(
            scene.setTrack(
                listOf(
                    TerrainScene.TrackPoint(47.85, 12.65, 500f),
                    TerrainScene.TrackPoint(47.86, 12.66, 500f)
                )
            )
        )
        assertTrue("the world follows a far flight", scene.originGeneration > generation)
        assertEquals(47.855, scene.originLat, 1e-3)
    }

    @Test
    fun theRootRingIsTheSameSizeOnTheMeridianAsAnywhereElse() {
        // The ring is picked by walking east and wrapping. Counting between
        // two column numbers instead selected every tile on the globe when
        // the ring reached over the line.
        val zoom = 7
        val across = 1 shl zoom
        fun columns(lon: Double, padLon: Double): Int {
            val x0 = juricabi.com.telemetry.utils.Elevation.tileX(lon - padLon, zoom)
            val x1 = juricabi.com.telemetry.utils.Elevation.tileX(lon + padLon, zoom)
            return ((x1 - x0) % across + across) % across + 1
        }
        val pad = 1.4
        assertEquals(columns(15.0, pad), columns(179.9, pad))
        assertEquals(columns(15.0, pad), columns(-179.9, pad))
        assertTrue("a ring is a handful of columns", columns(179.9, pad) < 8)
    }
}
