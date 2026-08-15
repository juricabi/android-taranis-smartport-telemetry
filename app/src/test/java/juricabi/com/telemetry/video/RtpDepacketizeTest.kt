package juricabi.com.telemetry.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raw-UDP path lives on this unwrapping: RTP headers stripped whatever
 * they carry, fragments reassembled only when every piece arrived, and the
 * codec read correctly off the first parameter set. These pin that against
 * hand-built packets, because no field log can show which byte was wrong.
 */
class RtpDepacketizeTest {

    /** An RTP packet: version 2, the fields this code reads, and a payload. */
    private fun rtp(
        seq: Int,
        payload: IntArray,
        timestamp: Long = 1000,
        marker: Boolean = false,
        csrcCount: Int = 0,
        extensionWords: Int = -1,
        padding: Int = 0
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(
            0x80 or (if (padding > 0) 0x20 else 0) or
                (if (extensionWords >= 0) 0x10 else 0) or csrcCount
        )
        out.write((if (marker) 0x80 else 0) or 96)
        out.write(seq shr 8)
        out.write(seq and 0xFF)
        for (shift in intArrayOf(24, 16, 8, 0)) {
            out.write((timestamp shr shift).toInt() and 0xFF)
        }
        repeat(4) { out.write(0) } // SSRC
        repeat(csrcCount * 4) { out.write(0) }
        if (extensionWords >= 0) {
            out.write(0); out.write(0)
            out.write(extensionWords shr 8); out.write(extensionWords and 0xFF)
            repeat(extensionWords * 4) { out.write(0) }
        }
        payload.forEach { out.write(it) }
        if (padding > 0) {
            repeat(padding - 1) { out.write(0) }
            out.write(padding)
        }
        return out.toByteArray()
    }

    private fun collect(codec: RtpCodec, vararg packets: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        val depacketizer = RtpDepacketizer(codec) { nal, _, _ -> nals.add(nal) }
        packets.forEach { depacketizer.feed(it, it.size) }
        return nals
    }

    @Test
    fun payloadStartWalksPastCsrcAndExtension() {
        val plain = rtp(1, intArrayOf(0x65))
        assertEquals(12, rtpPayloadStart(plain, plain.size))
        val dressed = rtp(1, intArrayOf(0x65), csrcCount = 2, extensionWords = 1)
        assertEquals(12 + 8 + 4 + 4, rtpPayloadStart(dressed, dressed.size))
    }

    @Test
    fun aSingleNalPassesThroughWhole() {
        val nals = collect(RtpCodec.H264, rtp(1, intArrayOf(0x65, 0x11, 0x22)))
        assertEquals(1, nals.size)
        assertArrayEquals(byteArrayOf(0x65, 0x11, 0x22), nals[0])
    }

    @Test
    fun fragmentsReassembleWithTheirRebuiltHeader() {
        // FU-A of an IDR (type 5, NRI 3): indicator 0x7C, start/middle/end
        val nals = collect(
            RtpCodec.H264,
            rtp(1, intArrayOf(0x7C, 0x85, 0x01)),
            rtp(2, intArrayOf(0x7C, 0x05, 0x02)),
            rtp(3, intArrayOf(0x7C, 0x45, 0x03))
        )
        assertEquals(1, nals.size)
        assertArrayEquals(byteArrayOf(0x65, 0x01, 0x02, 0x03), nals[0])
    }

    @Test
    fun aGapInSequenceAbandonsTheFragment() {
        val nals = collect(
            RtpCodec.H264,
            rtp(1, intArrayOf(0x7C, 0x85, 0x01)),
            rtp(3, intArrayOf(0x7C, 0x45, 0x03)) // seq 2 lost with the middle
        )
        assertTrue(nals.isEmpty())
    }

    @Test
    fun anAggregateSplitsIntoItsUnits() {
        // STAP-A: [len=2][0x67 0xAA] [len=1][0x68]
        val nals = collect(
            RtpCodec.H264,
            rtp(1, intArrayOf(0x18, 0, 2, 0x67, 0xAA, 0, 1, 0x68))
        )
        assertEquals(2, nals.size)
        assertArrayEquals(byteArrayOf(0x67, 0xAA.toByte()), nals[0])
        assertArrayEquals(byteArrayOf(0x68), nals[1])
    }

    @Test
    fun h265FragmentsRebuildTheTwoByteHeader() {
        // FU (payload header type 49 → 0x62 0x01), carrying an IDR_W_RADL (19)
        val nals = collect(
            RtpCodec.H265,
            rtp(1, intArrayOf(0x62, 0x01, 0x80 or 19, 0x0A)),
            rtp(2, intArrayOf(0x62, 0x01, 0x40 or 19, 0x0B))
        )
        assertEquals(1, nals.size)
        assertArrayEquals(byteArrayOf((19 shl 1).toByte(), 0x01, 0x0A, 0x0B), nals[0])
    }

    @Test
    fun paddingBytesNeverReachTheNal() {
        val nals = collect(RtpCodec.H264, rtp(1, intArrayOf(0x65, 0x11), padding = 3))
        assertEquals(1, nals.size)
        assertArrayEquals(byteArrayOf(0x65, 0x11), nals[0])
    }

    @Test
    fun aFragmentSurvivesTheSequenceNumbersWrapping() {
        val nals = collect(
            RtpCodec.H264,
            rtp(65535, intArrayOf(0x7C, 0x85, 0x01)),
            rtp(0, intArrayOf(0x7C, 0x45, 0x02)) // 65535 → 0 is no gap
        )
        assertEquals(1, nals.size)
        assertArrayEquals(byteArrayOf(0x65, 0x01, 0x02), nals[0])
    }

    @Test
    fun aZeroSizedAggregateEntryIsSkippedNotFatal() {
        // [len=0] junk entry, then [len=1][0x68] — the junk must not end
        // the walk or hand an empty unit onward
        val nals = collect(
            RtpCodec.H264,
            rtp(1, intArrayOf(0x18, 0, 0, 0, 1, 0x68))
        )
        assertEquals(1, nals.size)
        assertArrayEquals(byteArrayOf(0x68), nals[0])
    }

    @Test
    fun parameterSetsInsideAggregatesStillNameTheCodec() {
        // ffmpeg packs SPS+PPS into one STAP-A and VPS+SPS+PPS into one AP
        val stapA = rtp(1, intArrayOf(0x18, 0, 3, 0x67, 0x64, 0x00, 0, 1, 0x68))
        val ap = rtp(1, intArrayOf(0x60, 0x01, 0, 3, 0x40, 0x01, 0x0C))
        assertEquals(RtpCodec.H264, sniffRtpCodec(stapA, stapA.size))
        assertEquals(RtpCodec.H265, sniffRtpCodec(ap, ap.size))
    }

    @Test
    fun theCodecIsReadOffTheParameterSets() {
        val sps264 = rtp(1, intArrayOf(0x67, 0x64, 0x00))
        val vps265 = rtp(1, intArrayOf(0x40, 0x01, 0x0C))
        val idr = rtp(1, intArrayOf(0x65, 0x11))
        assertEquals(RtpCodec.H264, sniffRtpCodec(sps264, sps264.size))
        assertEquals(RtpCodec.H265, sniffRtpCodec(vps265, vps265.size))
        assertNull(sniffRtpCodec(idr, idr.size))
        assertNull(sniffRtpCodec(byteArrayOf(0x47, 0x00), 2)) // not RTP at all
    }
}
