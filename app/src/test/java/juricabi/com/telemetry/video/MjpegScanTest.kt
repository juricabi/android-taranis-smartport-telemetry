package juricabi.com.telemetry.video

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MJPEG scanner lives on the JPEG marks alone, because the cheap cameras
 * it exists for get everything else wrong. These pin that: frames come out
 * whole from between arbitrary multipart junk, markers that merely look like
 * an ending do not end a frame, and a broken tail is dropped, not delivered.
 */
class MjpegScanTest {

    private fun jpeg(vararg payload: Int): ByteArray {
        val body = payload.map { it.toByte() }.toByteArray()
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + body +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    private fun scan(stream: ByteArray, keepGoing: (ByteArray) -> Boolean = { true }): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        scanMjpegFrames(ByteArrayInputStream(stream)) {
            frames.add(it)
            keepGoing(it)
        }
        return frames
    }

    @Test
    fun liftsFramesWholeOutOfMultipartJunk() {
        val junk = "--boundary\r\nContent-Type: image/jpeg\r\nContent-Length: wrong\r\n\r\n"
            .toByteArray()
        val one = jpeg(0x01, 0x02, 0x03)
        val two = jpeg(0x04, 0x05)
        val frames = scan(junk + one + junk + two + junk)
        assertEquals(2, frames.size)
        assertArrayEquals(one, frames[0])
        assertArrayEquals(two, frames[1])
    }

    @Test
    fun stuffedAndRestartMarkersDoNotEndAFrameEarly() {
        // FF 00 byte stuffing and an FF D0 restart mark, both legal mid-frame
        val frame = jpeg(0xFF, 0x00, 0x11, 0xFF, 0xD0, 0x22)
        val frames = scan(frame)
        assertEquals(1, frames.size)
        assertArrayEquals(frame, frames[0])
    }

    @Test
    fun stopsWhenToldTo() {
        val frames = scan(jpeg(0x01) + jpeg(0x02), keepGoing = { false })
        assertEquals(1, frames.size)
    }

    @Test
    fun aFrameTheStreamDiedInsideIsDroppedNotDelivered() {
        val broken = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x33, 0x44)
        val frames = scan(jpeg(0x01) + broken)
        assertEquals(1, frames.size)
        assertTrue(frames[0].last() == 0xD9.toByte())
    }
}
