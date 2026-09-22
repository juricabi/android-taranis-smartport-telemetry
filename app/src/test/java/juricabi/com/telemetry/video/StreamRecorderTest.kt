package juricabi.com.telemetry.video

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The recording's timeline has to survive everything a flight does to the
 * stream — rebuilt players, wrapped clocks, stalls, a turned screen — and
 * the file has to survive the recorder being closed and reopened under it.
 */
class RecordingClockTest {

    @Test
    fun waitsForAKeyframeAndPinsItToTheWallClock() {
        val clock = RecordingClock(startedAtUs = 1_000_000)
        assertNull(clock.place(streamUs = 500, arrivalUs = 1_100_000, key = false))
        assertEquals(200_000L, clock.place(streamUs = 33_833, arrivalUs = 1_200_000, key = true))
        assertTrue(clock.tookDiscontinuity())
        // then the stream's own spacing, whatever the arrival jitter
        assertEquals(233_333L, clock.place(streamUs = 67_166, arrivalUs = 1_260_000, key = false))
        assertEquals(false, clock.tookDiscontinuity())
    }

    @Test
    fun aClockThatRestartsIsANewRunFromTheNextKeyframe() {
        val clock = RecordingClock(0)
        clock.place(10_000_000, 1_000_000, true)
        clock.place(10_033_000, 1_033_000, false)
        // a rebuilt player counts from zero again, five seconds later
        assertNull(clock.place(0, 6_000_000, false))
        assertEquals(6_033_000L, clock.place(33_000, 6_033_000, true))
        assertTrue(clock.tookDiscontinuity())
    }

    @Test
    fun aStallLongerThanTwoSecondsIsAGapOfItsRealLength() {
        val clock = RecordingClock(0)
        clock.place(0, 0, true)
        assertNull(clock.place(9_000_000, 3_000_000, false))
        assertEquals(3_100_000L, clock.place(9_100_000, 3_100_000, true))
    }

    @Test
    fun lostFramesResumeAtAKeyframeOnTheSameTimeline() {
        val clock = RecordingClock(0)
        clock.place(0, 0, true)
        clock.tookDiscontinuity()
        clock.lost()
        assertNull(clock.place(33_000, 40_000, false))
        assertEquals(66_000L, clock.place(66_000, 90_000, true))
        assertEquals(false, clock.tookDiscontinuity())
    }

    @Test
    fun aRunPinnedBehindWhatIsRecordedMovesWholeAndKeepsItsSpacing() {
        val clock = RecordingClock(0)
        clock.notBefore(5_000_000) // the file being appended to ends here
        // the wall clock says one second — behind the file's end
        val first = clock.place(0, 1_000_000, true)!!
        assertTrue(first > 5_000_000)
        assertEquals(first + 33_333, clock.place(33_333, 1_033_333, false))
        assertEquals(first + 66_666, clock.place(66_666, 1_066_666, false))
    }
}

@RunWith(RobolectricTestRunner::class)
class StreamRecorderTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Volatile private var now = 0L

    private fun record(file: File, append: Boolean, minFree: Long = 0, frames: StreamRecorder.() -> Unit): String? {
        val done = CountDownLatch(1)
        var ended: String? = null
        val recorder = StreamRecorder(file, append, 0, { _, why -> ended = why; done.countDown() },
            clockUs = { now }, minFreeBytes = minFree)
        recorder.frames()
        recorder.close()
        assertTrue("closed", done.await(5, TimeUnit.SECONDS))
        return ended
    }

    private fun StreamRecorder.gop(fromUs: Long) {
        for (i in 0 until 30) {
            now = fromUs + i * 33_333L
            val key = i == 0
            frame(
                RtpCodec.H264, byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41) + ByteArray(700),
                i * 33_333L, key, listOf(byteArrayOf(0x67, 1, 2), byteArrayOf(0x68, 3))
            )
        }
    }

    @Test
    fun aClosedRecordingIsWholePacketsAndAppendsCarryOn() {
        val file = File(folder.root, "flight.ts")
        assertNull(record(file, append = false) { gop(0) })
        val first = file.length()
        assertTrue(first > 0 && first % 188 == 0L)
        // the screen turned: a new recorder carries on in the same file
        assertNull(record(file, append = true) { gop(3_000_000) })
        assertTrue(file.length() > first && file.length() % 188 == 0L)
    }

    @Test
    fun anAppendNeverStepsBackInTimeAndHealsATornTail() {
        val file = File(folder.root, "turned.ts")
        record(file, append = false) { gop(10_000_000) }
        val before = lastTime90k(file.readBytes())!!
        // half a packet left by a kill
        file.appendBytes(ByteArray(100) { 0x47 })
        // the next screen's clock pins its first frame earlier than the file ends
        record(file, append = true) { gop(1_000_000) }
        val bytes = file.readBytes()
        assertEquals(0, bytes.size % 188)
        assertTrue(lastTime90k(bytes)!! > before)
    }

    @Test
    fun aRecordingThatNeverSawAKeyframeLeavesNoFile() {
        val file = File(folder.root, "empty.ts").also { it.createNewFile() }
        record(file, append = false) {
            frame(RtpCodec.H264, byteArrayOf(0, 0, 0, 1, 0x41, 1), 0, false, emptyList())
        }
        assertTrue(!file.exists())
    }

    @Test
    fun endsByItselfWhenTheStorageRunsLow() {
        val file = File(folder.root, "full.ts")
        val ended = record(file, append = false, minFree = Long.MAX_VALUE) { gop(0) }
        assertEquals("the storage is nearly full", ended)
    }

    @Test
    fun anUnwritableFileEndsTheRecordingSayingSo() {
        val dir = File(folder.root, "dir.ts").also { it.mkdirs() }
        val ended = record(dir, append = false) { gop(0) }
        assertTrue(ended!!.startsWith("it could not be written"))
    }

    @Test
    fun troubleFromTheSourceEndsItWithTheSourcesWords() {
        val file = File(folder.root, "t.ts")
        val ended = record(file, append = false) { trouble("the encoder failed") }
        assertEquals("the encoder failed", ended)
    }
}
