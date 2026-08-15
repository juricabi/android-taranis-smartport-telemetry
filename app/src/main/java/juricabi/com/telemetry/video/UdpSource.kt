package juricabi.com.telemetry.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.view.TextureView
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import juricabi.com.telemetry.utils.DebugLog

/** Which codec the stream's parameter sets betrayed. */
internal enum class RtpCodec(val mime: String) {
    H264(MediaFormat.MIMETYPE_VIDEO_AVC),
    H265(MediaFormat.MIMETYPE_VIDEO_HEVC)
}

/**
 * Reads the codec off an RTP payload before any depacketizing is possible —
 * the framing itself differs by codec, but parameter sets always ride whole
 * in their own packets, and their first bytes cannot be mistaken for each
 * other: an H.264 SPS starts x7 under the 5-bit type, an H.265 VPS or SPS
 * is 0x40/0x42 followed by 0x01. Anything else says nothing yet.
 */
internal fun sniffRtpCodec(packet: ByteArray, length: Int): RtpCodec? {
    val at = rtpPayloadStart(packet, length) ?: return null
    val b0 = packet[at].toInt() and 0xFF
    if (b0 and 0x9F == 0x07) return RtpCodec.H264
    if ((b0 == 0x40 || b0 == 0x42) && at + 1 < length &&
        packet[at + 1].toInt() == 0x01
    ) return RtpCodec.H265
    return null
}

/**
 * Where the payload begins inside an RTP datagram, or null for anything that
 * is not RTP version 2: past the fixed header, the contributing sources and
 * the extension, if any.
 */
internal fun rtpPayloadStart(packet: ByteArray, length: Int): Int? {
    if (length < 12) return null
    val b0 = packet[0].toInt() and 0xFF
    if (b0 shr 6 != 2) return null
    var at = 12 + (b0 and 0x0F) * 4
    if (b0 and 0x10 != 0) {
        if (at + 4 > length) return null
        val words = ((packet[at + 2].toInt() and 0xFF) shl 8) or
            (packet[at + 3].toInt() and 0xFF)
        at += 4 + words * 4
    }
    return if (at < length) at else null
}

/**
 * Strips the RTP wrapping and hands whole NAL units over: single units as
 * they come, fragments (FU-A / FU) reassembled, aggregates (STAP-A / AP)
 * split apart. A gap in the sequence numbers while a fragment is open
 * abandons it — half a picture fed onward decodes as garbage.
 *
 * One feeder thread; the marker bit and the timestamp are passed through so
 * the caller can group units into access units.
 */
internal class RtpDepacketizer(
    private val codec: RtpCodec,
    private val onNal: (nal: ByteArray, timestamp: Long, marker: Boolean) -> Unit
) {
    private val fragment = ByteArrayOutputStream(64 * 1024)
    private var fragmentOpen = false
    private var lastSeq = -1

    fun feed(packet: ByteArray, length: Int) {
        val at = rtpPayloadStart(packet, length) ?: return
        val seq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val timestamp = ((packet[4].toLong() and 0xFF) shl 24) or
            ((packet[5].toLong() and 0xFF) shl 16) or
            ((packet[6].toLong() and 0xFF) shl 8) or
            (packet[7].toLong() and 0xFF)
        val marker = packet[1].toInt() and 0x80 != 0
        var end = length
        if (packet[0].toInt() and 0x20 != 0) end -= packet[length - 1].toInt() and 0xFF
        if (end <= at) return
        if (fragmentOpen && lastSeq != -1 && seq != (lastSeq + 1) and 0xFFFF) {
            fragmentOpen = false
        }
        lastSeq = seq
        when (codec) {
            RtpCodec.H264 -> h264(packet, at, end, timestamp, marker)
            RtpCodec.H265 -> h265(packet, at, end, timestamp, marker)
        }
    }

    private fun h264(p: ByteArray, at: Int, end: Int, ts: Long, marker: Boolean) {
        when (val type = p[at].toInt() and 0x1F) {
            in 1..23 -> onNal(p.copyOfRange(at, end), ts, marker)
            24 -> { // STAP-A: [size][NAL]...
                var i = at + 1
                while (i + 2 <= end) {
                    val size = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                    i += 2
                    if (i + size > end) break
                    onNal(p.copyOfRange(i, i + size), ts, marker && i + size >= end)
                    i += size
                }
            }
            28 -> { // FU-A: [indicator][S E R type][fragment]
                if (at + 2 > end) return
                val fu = p[at + 1].toInt() and 0xFF
                if (fu and 0x80 != 0) {
                    fragment.reset()
                    fragment.write((p[at].toInt() and 0xE0) or (fu and 0x1F))
                    fragmentOpen = true
                }
                if (!fragmentOpen) return
                fragment.write(p, at + 2, end - at - 2)
                if (fu and 0x40 != 0) {
                    fragmentOpen = false
                    onNal(fragment.toByteArray(), ts, marker)
                }
            }
            else -> {} // 25..27, 29: interleaved modes nothing here sends
        }
    }

    private fun h265(p: ByteArray, at: Int, end: Int, ts: Long, marker: Boolean) {
        if (at + 2 > end) return
        when (val type = (p[at].toInt() shr 1) and 0x3F) {
            48 -> { // AP: [size][NAL]...
                var i = at + 2
                while (i + 2 <= end) {
                    val size = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                    i += 2
                    if (i + size > end) break
                    onNal(p.copyOfRange(i, i + size), ts, marker && i + size >= end)
                    i += size
                }
            }
            49 -> { // FU: [2-byte header][S E type][fragment]
                if (at + 3 > end) return
                val fu = p[at + 2].toInt() and 0xFF
                if (fu and 0x80 != 0) {
                    fragment.reset()
                    fragment.write((p[at].toInt() and 0x81) or ((fu and 0x3F) shl 1))
                    fragment.write(p[at + 1].toInt())
                    fragmentOpen = true
                }
                if (!fragmentOpen) return
                fragment.write(p, at + 3, end - at - 3)
                if (fu and 0x40 != 0) {
                    fragmentOpen = false
                    onNal(fragment.toByteArray(), ts, marker)
                }
            }
            else -> onNal(p.copyOfRange(at, end), ts, marker)
        }
    }
}

/** The NAL type under either codec's header. */
internal fun nalType(codec: RtpCodec, nal: ByteArray): Int = when (codec) {
    RtpCodec.H264 -> nal[0].toInt() and 0x1F
    RtpCodec.H265 -> (nal[0].toInt() shr 1) and 0x3F
}

/**
 * A raw pushed stream: RTP datagrams carrying H.264 or H.265 straight at a
 * port on this phone — the OpenIPC / wfb-ng ground stations and
 * QGroundControl-style senders, which have no server to dial and begin
 * talking the moment the link is up. The NAL units go to the phone's
 * hardware decoder and every frame renders the moment it is decoded; there
 * is no buffer, so the latency is the link's own.
 *
 * The codec is read off the stream itself — senders repeat their parameter
 * sets, so listening briefly always answers — and decoding starts at the
 * first keyframe after that.
 */
class UdpSource(
    private val port: Int,
    private val events: VideoSource.Events
) : VideoSource {

    @Volatile private var running = false
    @Volatile private var live = false
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var surface: Surface? = null
    private var view: TextureView? = null
    @Volatile private var pictureWidth = 0
    @Volatile private var pictureHeight = 0

    // whole access units, receiver to decoder; a burst beyond the decoder's
    // pace drops the backlog rather than growing a latency debt
    private val units = ArrayBlockingQueue<Pair<ByteArray, Long>>(4)

    private val refitOnLayout = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        refit()
    }

    override fun refit() {
        view?.fitPicture(pictureWidth, pictureHeight)
    }

    override fun start(view: TextureView) {
        DebugLog.note("Video", "udp listen :$port")
        this.view = view
        view.addOnLayoutChangeListener(refitOnLayout)
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: android.graphics.SurfaceTexture, w: Int, h: Int) {
                surface = Surface(t)
            }
            override fun onSurfaceTextureSizeChanged(t: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(t: android.graphics.SurfaceTexture): Boolean {
                surface?.release()
                surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(t: android.graphics.SurfaceTexture) {}
        }
        view.surfaceTexture?.let { surface = Surface(it) }
        running = true
        Thread({ receive() }, "udp-video").start()
    }

    private fun receive() {
        val socket = try {
            DatagramSocket(port).also {
                it.receiveBufferSize = 1 shl 19
                it.soTimeout = 1000
            }
        } catch (e: Exception) {
            if (running) events.onTrouble("UDP port $port cannot be opened — ${e.message}")
            return
        }
        this.socket = socket
        val packet = DatagramPacket(ByteArray(65536), 65536)
        var codec: RtpCodec? = null
        var depacketizer: RtpDepacketizer? = null
        val csd = LinkedHashMap<Int, ByteArray>() // parameter sets, in arrival order
        var decoder: Thread? = null
        // the access unit being gathered: NALs sharing one RTP timestamp
        var au = ByteArrayOutputStream(256 * 1024)
        var auTimestamp = -1L
        var auHasKeyframe = false
        var sentFirstKeyframe = false
        var lastHeard = System.currentTimeMillis()
        var everHeard = false

        fun finishAu() {
            if (au.size() == 0) return
            val bytes = au.toByteArray()
            au.reset()
            // decoding must begin at a keyframe; the picture before one is
            // built on frames never seen
            if (!sentFirstKeyframe && !auHasKeyframe) { auHasKeyframe = false; return }
            sentFirstKeyframe = true
            auHasKeyframe = false
            if (!units.offer(Pair(bytes, auTimestamp))) {
                units.clear()
                units.offer(Pair(bytes, auTimestamp))
            }
        }

        try {
            while (running) {
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    val quiet = System.currentTimeMillis() - lastHeard
                    if (!everHeard && quiet > 8000) {
                        events.onTrouble(
                            "nothing arrives on UDP port $port — the sender " +
                                "must be pointed at this phone's address"
                        )
                        return
                    }
                    if (everHeard && codec == null && quiet > 8000) {
                        events.onTrouble(
                            "UDP port $port carries data but no H.264/H.265 " +
                                "parameter sets came — is the sender using RTP?"
                        )
                        return
                    }
                    if (live && quiet > 4000) {
                        live = false
                        DebugLog.note("Video", "udp stream went quiet")
                        events.onIdle()
                    }
                    continue
                }
                lastHeard = System.currentTimeMillis()
                if (!everHeard) {
                    everHeard = true
                    val b = packet.data
                    if (packet.length >= 188 && b[0].toInt() == 0x47 &&
                        packet.length % 188 == 0
                    ) {
                        events.onTrouble(
                            "UDP port $port carries MPEG-TS, which this does " +
                                "not read — set the sender to RTP"
                        )
                        return
                    }
                }
                if (codec == null) {
                    codec = sniffRtpCodec(packet.data, packet.length) ?: continue
                    DebugLog.note("Video", "udp stream is ${codec.name} (RTP)")
                    val chosen = codec
                    depacketizer = RtpDepacketizer(chosen) { nal, ts, marker ->
                        if (ts != auTimestamp && auTimestamp != -1L) finishAu()
                        auTimestamp = ts
                        val type = nalType(chosen, nal)
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
                depacketizer?.feed(packet.data, packet.length)
                // the decoder starts once the stream has told it what it is
                // and the screen has given it somewhere to draw
                if (decoder == null && surface != null &&
                    (codec == RtpCodec.H264 && csd.size >= 2 ||
                        codec == RtpCodec.H265 && csd.size >= 3)
                ) {
                    val d = Thread({ decode(codec!!, csd.values.toList()) }, "udp-decode")
                    decoder = d
                    d.start()
                }
            }
        } catch (e: Exception) {
            // stop() closes the socket under the blocked receive; only a
            // failure while still wanted is trouble
            if (running) events.onTrouble("listening on UDP port $port failed — ${e.message}")
        } finally {
            socket.close()
        }
    }

    private fun decode(codec: RtpCodec, parameterSets: List<ByteArray>) {
        val startCode = byteArrayOf(0, 0, 0, 1)
        val decoder = try {
            MediaCodec.createDecoderByType(codec.mime).also {
                val format = MediaFormat.createVideoFormat(codec.mime, 1920, 1080)
                // the parameter sets as one Annex-B run — the sizes in them,
                // not this nominal 1920x1080, decide the real picture
                val bytes = ByteArrayOutputStream()
                for (set in parameterSets) {
                    bytes.write(startCode)
                    bytes.write(set)
                }
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                it.configure(format, surface, null, 0)
                it.start()
            }
        } catch (e: Exception) {
            DebugLog.note("Video", "udp decoder failed: $e")
            if (running) events.onTrouble("the ${codec.name} decoder failed to start")
            return
        }
        DebugLog.note("Video", "udp ${codec.name} decoder up")
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val (bytes, timestamp) = units.poll(
                    100, java.util.concurrent.TimeUnit.MILLISECONDS
                ) ?: continue
                val at = decoder.dequeueInputBuffer(100_000)
                if (at < 0) continue // decoder jammed; the unit is dropped
                decoder.getInputBuffer(at)?.put(bytes)
                // 90 kHz RTP ticks to microseconds; frames render on arrival,
                // so only monotony matters, not the epoch
                decoder.queueInputBuffer(at, 0, bytes.size, timestamp * 100 / 9, 0)
                while (true) {
                    val out = decoder.dequeueOutputBuffer(info, 0)
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = decoder.outputFormat
                        pictureWidth = f.getInteger(MediaFormat.KEY_WIDTH)
                        pictureHeight = f.getInteger(MediaFormat.KEY_HEIGHT)
                        DebugLog.note(
                            "Video", "udp picture ${pictureWidth}x$pictureHeight"
                        )
                        view?.post { refit() }
                        continue
                    }
                    if (out < 0) break
                    decoder.releaseOutputBuffer(out, true)
                    if (!live) {
                        live = true
                        DebugLog.note("Video", "udp first frame rendered")
                        events.onLive()
                    }
                }
            }
        } catch (e: Exception) {
            // stop() closing things under the loop is the usual way here
            if (running) {
                DebugLog.note("Video", "udp decode error: $e")
                events.onTrouble("decoding failed — ${e.message}")
            }
        } finally {
            try {
                decoder.stop()
            } catch (e: Exception) {
            }
            decoder.release()
        }
    }

    override fun stop() {
        DebugLog.note("Video", "udp stop")
        running = false
        socket?.close() // unblocks the receiver mid-wait
        view?.surfaceTextureListener = null
        view?.removeOnLayoutChangeListener(refitOnLayout)
        view = null
        surface?.release()
        surface = null
    }
}
