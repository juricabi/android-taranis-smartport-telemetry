package juricabi.com.telemetry.video

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A USB camera's frames reach the recorder with the jitter of whatever ran
 * before them; the recording must play at the camera's own even beat anyway.
 * The jitter here is the kind a phone measured: ±25 ms around a steady 30 fps.
 */
class FrameClockTest {

    private val beat = 1_000_000.0 / 30

    /** Arrivals at an even [periodUs], each late by up to [jitterUs] — a fixed pseudo-random pattern. */
    private fun arrivals(count: Int, periodUs: Double, jitterUs: Int, startUs: Long = 5_000_000): List<Long> {
        var seed = 12345L
        return (0 until count).map { i ->
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            startUs + (i * periodUs).toLong() + seed % (jitterUs + 1)
        }
    }

    private fun steps(times: List<Long>) = times.zipWithNext { a, b -> b - a }

    @Test
    fun jitteredArrivalsComeOutOnAnEvenBeat() {
        val clock = FrameClock(30)
        val stamped = arrivals(300, beat, 25_000).map(clock::stamp)
        // the arrivals step anywhere from a few ms to near 60; the stamps may not
        for (step in steps(stamped).drop(20)) {
            assertTrue("step $step", abs(step - beat) < 3_500)
        }
        // and the pace is the camera's: ten seconds of frames last ten seconds
        val span = stamped.last() - stamped.first()
        assertTrue("span $span", abs(span - 299 * beat) < beat)
    }

    @Test
    fun aCameraSlowerThanItSaysIsFollowed() {
        val clock = FrameClock(30)
        val period = 1_000_000.0 / 25
        val stamped = arrivals(400, period, 20_000).map(clock::stamp)
        val late = steps(stamped).takeLast(100)
        assertEquals(period, late.average(), 1_500.0)
    }

    @Test
    fun aPauseIsPinnedWhereTheNextFrameArrived() {
        val clock = FrameClock(30)
        val before = arrivals(60, beat, 10_000)
        before.forEach { clock.stamp(it) }
        val after = before.last() + 2_000_000
        assertEquals(after, clock.stamp(after))
    }

    @Test
    fun timeNeverRunsBackwards() {
        val clock = FrameClock(30)
        // bunched and reordered arrivals, the worst a delayed callback can do
        val times = listOf(0L, 5_000, 6_000, 90_000, 91_000, 92_000, 60_000, 200_000, 150_000)
            .map { clock.stamp(it + 1_000_000) }
        steps(times).forEach { assertTrue("step $it", it > 0) }
    }
}
