package juricabi.com.telemetry.ui

import juricabi.com.telemetry.maps.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReplayTrackTailTest {
    @Test
    fun pausedSeekHeadComesFromRecordedRouteRatherThanOldPresentation() {
        val oldPresentation = Position(45.0, 15.0)
        val turn = Position(45.001, 15.0)
        val target = Position(45.001, 15.001)
        val published = listOf(Position(45.0, 14.999), turn, target)

        val head = replayTrackTail(published, target)

        assertEquals(published, head)
        assertFalse(head.contains(oldPresentation))
    }

    @Test
    fun targetIsAddedWithoutExceedingOverlapLimit() {
        val route = (0 until 8).map { Position(45.0, 15.0 + it / 1000.0) }
        val target = Position(45.0, 15.1)

        val head = replayTrackTail(route, target, limit = 4)

        assertEquals(listOf(route[5], route[6], route[7], target), head)
    }
}
