package juricabi.com.telemetry.video

import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Bench-only: replays a captured RTP datagram file (4-byte length prefix per
 * packet) through the exact pipeline UdpSource runs ahead of MediaCodec —
 * sniff, depacketize, access-unit assembly — and writes the Annex-B stream
 * a phone's decoder would have been fed, for ffmpeg to validate.
 */
class UdpBenchTest {

    @Test
    fun replayCapture() {
        val path = System.getenv("UDP_CAPTURE") ?: return
        assumeTrue(File(path).exists())
        val out = File("$path.h26x").outputStream()
        val input = DataInputStream(FileInputStream(path))

        var codec: RtpCodec? = null
        var depacketizer: RtpDepacketizer? = null
        val csd = LinkedHashMap<Int, ByteArray>()
        val au = java.io.ByteArrayOutputStream(256 * 1024)
        var auTimestamp = -1L
        var auHasKeyframe = false
        var needKeyframe = true
        var aus = 0
        var dropped = 0
        var biggest = 0
        val nalTypes = LinkedHashMap<Int, Int>()

        fun finishAu() {
            if (au.size() == 0) return
            val bytes = au.toByteArray()
            val key = auHasKeyframe
            au.reset()
            auHasKeyframe = false
            if (needKeyframe && !key) { dropped++; return }
            needKeyframe = false
            aus++
            if (bytes.size > biggest) biggest = bytes.size
            out.write(bytes)
        }

        while (true) {
            val len = try { input.readInt() } catch (e: Exception) { break }
            val packet = ByteArray(len)
            input.readFully(packet)
            if (codec == null) {
                codec = sniffRtpCodec(packet, len) ?: continue
                val chosen = codec
                depacketizer = RtpDepacketizer(chosen) { nal, ts, marker ->
                    if (ts != auTimestamp && auTimestamp != -1L) finishAu()
                    auTimestamp = ts
                    val type = nalType(chosen, nal)
                    nalTypes.merge(type, 1, Int::plus)
                    val parameterSet = when (chosen) {
                        RtpCodec.H264 -> type == 7 || type == 8
                        RtpCodec.H265 -> type in 32..34
                    }
                    if (parameterSet && csd.size < 3) csd[type] = nal
                    auHasKeyframe = auHasKeyframe || when (chosen) {
                        RtpCodec.H264 -> type == 5
                        RtpCodec.H265 -> type in 16..21
                    }
                    au.write(0); au.write(0); au.write(0); au.write(1)
                    au.write(nal, 0, nal.size)
                    if (marker) finishAu()
                }
            }
            depacketizer?.feed(packet, len)
        }
        finishAu()
        out.close()
        println("codec=$codec csd=${csd.keys} aus=$aus droppedBeforeKey=$dropped biggestAu=$biggest nalTypes=$nalTypes")
    }
}
