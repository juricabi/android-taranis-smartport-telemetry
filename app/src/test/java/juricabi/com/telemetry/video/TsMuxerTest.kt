package juricabi.com.telemetry.video

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recording is only as good as the file a player later opens, and no field
 * report can say which byte of a transport stream was wrong. These read the
 * muxer's output back the way a demuxer does — packet by packet, table by
 * table, PES by PES — against hand-built access units.
 */
class TsMuxerTest {

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

    private fun idr(size: Int) = byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(size) { (it % 251 + 1).toByte() }
    private fun slice(size: Int) = byteArrayOf(0, 0, 0, 1, 0x41) + ByteArray(size) { (it % 7 + 1).toByte() }

    private class Packet(val bytes: ByteArray) {
        val start get() = bytes[1].toInt() and 0x40 != 0
        val pid get() = (bytes[1].toInt() and 0x1F shl 8) or (bytes[2].toInt() and 0xFF)
        val counter get() = bytes[3].toInt() and 0x0F
        val adaptation get() = bytes[3].toInt() and 0x20 != 0
        val adaptationLength get() = if (adaptation) bytes[4].toInt() and 0xFF else -1
        val flags get() = if (adaptationLength > 0) bytes[5].toInt() and 0xFF else 0
        val payload: ByteArray
            get() = bytes.copyOfRange(4 + if (adaptation) adaptationLength + 1 else 0, 188)
        val pcr: Long
            get() {
                val b = { i: Int -> bytes[6 + i].toLong() and 0xFF }
                return (b(0) shl 25) or (b(1) shl 17) or (b(2) shl 9) or (b(3) shl 1) or (b(4) shr 7)
            }
    }

    private fun packets(ts: ByteArray): List<Packet> {
        assertEquals("whole packets only", 0, ts.size % 188)
        return (0 until ts.size / 188).map { Packet(ts.copyOfRange(it * 188, it * 188 + 188)) }
            .onEach { assertEquals(0x47, it.bytes[0].toInt()) }
    }

    /** The PES packets on the video PID, each reassembled whole. */
    private fun pes(packets: List<Packet>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var current: ByteArrayOutputStream? = null
        for (p in packets.filter { it.pid == TsMuxer.VIDEO_PID }) {
            if (p.start) {
                current?.let { out += it.toByteArray() }
                current = ByteArrayOutputStream()
            }
            current!!.write(p.payload)
        }
        current?.let { out += it.toByteArray() }
        return out
    }

    private fun pts(pes: ByteArray): Long {
        val b = { i: Int -> pes[9 + i].toLong() and 0xFF }
        return ((b(0) shr 1 and 0x07) shl 30) or (b(1) shl 22) or ((b(2) shr 1) shl 15) or
            (b(3) shl 7) or (b(4) shr 1)
    }

    private fun mux(block: TsMuxer.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { TsMuxer(it).block() }.toByteArray()

    @Test
    fun keyframeCarriesTablesParameterSetsAndTheFrameIntact() {
        val frame = idr(3000)
        val ts = mux { frame(RtpCodec.H264, frame, 90_000, true, listOf(sps, pps), true) }
        val packets = packets(ts)
        assertEquals(TsMuxer.PAT_PID, packets[0].pid)
        assertEquals(TsMuxer.PMT_PID, packets[1].pid)
        val body = pes(packets).single()
        // PES start code, video stream, PTS-only header
        assertArrayEquals(byteArrayOf(0, 0, 1, 0xE0.toByte()), body.copyOfRange(0, 4))
        assertEquals(0x80, body[7].toInt() and 0xC0)
        val es = body.copyOfRange(14, body.size)
        val expected = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) +
            annexB(sps) + annexB(pps) + frame
        assertArrayEquals(expected, es)
    }

    @Test
    fun tablesHaveValidCrcsAndNameTheCodec() {
        for ((codec, type) in listOf(RtpCodec.H264 to 0x1B, RtpCodec.H265 to 0x24)) {
            val ts = mux { frame(codec, idr(10), 0, true, emptyList(), false) }
            val packets = packets(ts)
            for (table in packets.take(2)) {
                val section = table.payload.copyOfRange(1, table.payload.size)
                val length = (section[1].toInt() and 0x0F shl 8) or (section[2].toInt() and 0xFF)
                // the MPEG CRC over a section including its own CRC is zero
                assertEquals(0, TsMuxer.crc32(section, 3 + length))
            }
            val pmt = packets[1].payload
            assertEquals(type, pmt[1 + 12].toInt() and 0xFF)
        }
    }

    @Test
    fun ptsLeadsPcrAndBothFollowTheClock() {
        val ts = mux {
            frame(RtpCodec.H264, idr(100), 90_000, true, listOf(sps, pps), false)
            frame(RtpCodec.H264, slice(100), 93_000, false, emptyList(), false)
        }
        val packets = packets(ts)
        val starts = packets.filter { it.pid == TsMuxer.VIDEO_PID && it.start }
        assertEquals(listOf(90_000L, 93_000L), starts.map { it.pcr })
        assertEquals(listOf(99_000L, 102_000L), pes(packets).map(::pts))
    }

    @Test
    fun everySizeSplitsAndStuffsIntoWholePackets() {
        // around the first packet's 176 bytes of room, and the 184 after
        for (size in listOf(1, 150, 155, 156, 157, 158, 170, 171, 330, 339, 340, 341, 342, 5000)) {
            val frame = slice(size)
            val ts = mux { frame(RtpCodec.H264, frame, 0, false, emptyList(), false) }
            val body = pes(packets(ts)).single()
            assertArrayEquals(
                "size $size", byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) + frame,
                body.copyOfRange(14, body.size)
            )
        }
    }

    @Test
    fun continuityCountsOnPerPid() {
        val ts = mux {
            repeat(40) { frame(RtpCodec.H264, if (it % 10 == 0) idr(900) else slice(400), it * 3000L, it % 10 == 0, listOf(sps, pps), false) }
        }
        packets(ts).groupBy { it.pid }.forEach { (pid, list) ->
            list.zipWithNext().forEach { (a, b) ->
                assertEquals("pid $pid", (a.counter + 1) and 0x0F, b.counter)
            }
        }
    }

    @Test
    fun discontinuityAndRandomAccessAreFlaggedOnlyWhereTheyAre() {
        val ts = mux {
            frame(RtpCodec.H264, idr(50), 0, true, listOf(sps, pps), true)
            frame(RtpCodec.H264, slice(50), 3000, false, emptyList(), false)
        }
        val (first, second) = packets(ts).filter { it.pid == TsMuxer.VIDEO_PID && it.start }
        assertEquals(0xC0, first.flags and 0xC0)
        assertEquals(0, second.flags and 0xC0)
    }

    @Test
    fun inBandParameterSetsAndDelimitersAreNotRepeated() {
        val frame = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) + annexB(sps) + annexB(pps) + idr(80)
        val ts = mux { frame(RtpCodec.H264, frame, 0, true, listOf(sps, pps), false) }
        val body = pes(packets(ts)).single()
        assertArrayEquals(frame, body.copyOfRange(14, body.size))
    }

    @Test
    fun outOfBandParameterSetsGoAfterTheStreamsOwnDelimiter() {
        val aud = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
        val slice = idr(80)
        val ts = mux { frame(RtpCodec.H264, aud + slice, 0, true, listOf(sps, pps), false) }
        val body = pes(packets(ts)).single()
        assertArrayEquals(aud + annexB(sps) + annexB(pps) + slice, body.copyOfRange(14, body.size))
    }

    @Test
    fun theFirstTablesOfAMuxerAreFlaggedAsARestart() {
        val packets = packets(mux {
            frame(RtpCodec.H264, idr(10), 0, true, emptyList(), false)
            frame(RtpCodec.H264, idr(10), 3000, true, emptyList(), false)
        })
        val tables = packets.filter { it.pid == TsMuxer.PAT_PID }
        assertEquals(0x80, tables[0].flags and 0x80)
        assertFalse(tables[1].adaptation)
    }

    @Test
    fun theLastTimeInAFileIsReadBackFromItsTail() {
        val ts = mux {
            frame(RtpCodec.H264, idr(400), 90_000, true, listOf(sps, pps), false)
            frame(RtpCodec.H264, slice(900), 93_000, false, emptyList(), false)
            frame(RtpCodec.H264, slice(30), 96_000, false, emptyList(), false)
        }
        assertEquals(96_000L, lastTime90k(ts))
        assertEquals(null, lastTime90k(ByteArray(0)))
    }

    @Test
    fun annexBPrefixesOnlyWhatLacksAStartCode() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + sps, annexB(sps))
        val four = byteArrayOf(0, 0, 0, 1) + sps
        val three = byteArrayOf(0, 0, 1) + sps
        assertTrue(annexB(four) === four)
        assertTrue(annexB(three) === three)
        assertEquals(listOf(9, 7, 8, 5), nalTypes(RtpCodec.H264, byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) + three + annexB(pps) + idr(4)))
        assertFalse(nalTypes(RtpCodec.H265, byteArrayOf(0, 0, 1, 0x40, 1)).isEmpty())
    }
}
