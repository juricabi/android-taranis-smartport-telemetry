package juricabi.com.telemetry.video

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 on the phone's hardware encoder, for the sources whose pictures are
 * not already H.264 — a USB camera's raw frames, an MJPEG stream's JPEGs.
 * The access units come out into the same kind of [RecordSink] the network
 * streams feed, so every recording is the same crash-proof transport stream,
 * whatever carried the picture.
 *
 * Used from one thread, the source's frame thread, and synchronously: the
 * source must never stall behind a recording.
 */
internal abstract class H264Encoder(
    val width: Int,
    val height: Int,
    fps: Int,
    surfaceInput: Boolean
) {
    protected val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val info = MediaCodec.BufferInfo()
    private var parameterSets = emptyList<ByteArray>()
    protected val clock = FrameClock(fps)

    /** Where a surface-fed encoder takes its pictures; null for one fed buffers. */
    protected val inputSurface: Surface? = run {
        var surface: Surface? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                if (surfaceInput) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                // flexible, filled through the input Image: its planes say where
                // every sample goes, so no device's own colour layout needs knowing
                else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            // ~5.5 Mbit/s at 720p, 1.8 at an analog receiver's 640×480
            format.setInteger(MediaFormat.KEY_BIT_RATE, maxOf(width * height * 6, 1_000_000))
            format.setInteger(MediaFormat.KEY_FRAME_RATE, if (fps > 0) fps else 30)
            // a keyframe a second: where a resumed or cut recording can begin
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = if (surfaceInput) codec.createInputSurface() else null
            codec.start()
            surface
        } catch (e: Exception) {
            // a start that fails after the surface was made lets it go too
            surface?.release()
            codec.release()
            throw e
        }
    }

    /**
     * Hands on whatever the encoder has finished. [restamp] smooths the times
     * of pictures that carried only the moment they were drawn.
     */
    protected fun drain(sink: RecordSink, restamp: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                parameterSets = listOfNotNull(f.getByteBuffer("csd-0"), f.getByteBuffer("csd-1"))
                    .map { it.bytes() }
                continue
            }
            if (index < 0) return
            val out = codec.getOutputBuffer(index)
            if (out != null && info.size > 0) {
                out.position(info.offset)
                out.limit(info.offset + info.size)
                val bytes = out.bytes()
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    parameterSets = listOf(bytes)
                } else {
                    val time = info.presentationTimeUs
                    sink.frame(
                        RtpCodec.H264, bytes, if (restamp) clock.stamp(time) else time,
                        info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, parameterSets
                    )
                }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    fun release() {
        try {
            codec.stop()
        } catch (e: Exception) {
            // already failed; releasing is all that is left
        }
        codec.release()
        inputSurface?.release()
    }
}

/**
 * A USB camera's NV21 frames, copied into the encoder's input buffers. A
 * frame the encoder has no room for is dropped, never waited on — the camera
 * must not stall behind a recording.
 */
internal class Nv21Encoder(width: Int, height: Int, fps: Int) :
    H264Encoder(width, height, fps, surfaceInput = false) {

    private val frame = ByteArray(width * height * 3 / 2)

    /**
     * One NV21 picture of this encoder's size, heard at [arrivalUs]; what
     * comes out goes to [sink]. Every frame is stamped, even one the encoder
     * has no room for, so a dropped frame leaves its slot in the timeline.
     */
    fun encode(nv21: ByteBuffer, arrivalUs: Long, sink: RecordSink) {
        val timeUs = clock.stamp(arrivalUs)
        val index = codec.dequeueInputBuffer(0)
        if (index >= 0) {
            nv21.duplicate().get(frame)
            // the whole buffer, not w×h×1.5: an encoder that pads its rows
            // lays the planes out past that, and reads what it is told
            val length = codec.getInputBuffer(index)?.capacity() ?: frame.size
            val image = codec.getInputImage(index)
                ?: throw IllegalStateException("the phone's encoder takes no pictures")
            val y = image.planes[0]
            for (row in 0 until height) {
                y.buffer.position(row * y.rowStride)
                y.buffer.put(frame, row * width, width)
            }
            fillChroma(image.planes[1], image.planes[2])
            codec.queueInputBuffer(index, 0, length, timeUs, 0)
        }
        drain(sink, restamp = false)
    }

    private val chromaRow = ByteArray(width)
    private val halfRow = ByteArray(width / 2)

    /**
     * NV21 interleaves V then U, a pair per 2×2 block. Written a row at a
     * time — a byte at a time is millions of buffer calls a second at 720p,
     * on the thread the camera delivers on. A semi-planar encoder's two
     * chroma planes are one interleaved run seen from both ends, so the row
     * goes in whole through whichever plane starts it; a planar one takes
     * each half-row by itself; anything else, byte by byte.
     */
    private fun fillChroma(u: Image.Plane, v: Image.Plane) {
        val base = width * height
        val pairs = width / 2
        val order = if (u.pixelStride == 2 && v.pixelStride == 2) interleaving(u, v) else 0
        for (row in 0 until height / 2) {
            val src = base + row * width
            when {
                // V first, as NV21 itself: the row goes in as it is
                order < 0 -> putRow(v, u, row, frame, src, width)
                // U first, NV12: the pairs are swapped on the way
                order > 0 -> {
                    for (k in 0 until pairs) {
                        chromaRow[2 * k] = frame[src + 2 * k + 1]
                        chromaRow[2 * k + 1] = frame[src + 2 * k]
                    }
                    putRow(u, v, row, chromaRow, 0, width)
                }
                u.pixelStride == 1 && v.pixelStride == 1 -> {
                    for (k in 0 until pairs) halfRow[k] = frame[src + 2 * k]
                    putRow(v, null, row, halfRow, 0, pairs)
                    for (k in 0 until pairs) halfRow[k] = frame[src + 2 * k + 1]
                    putRow(u, null, row, halfRow, 0, pairs)
                }
                else -> for (k in 0 until pairs) {
                    v.buffer.put(row * v.rowStride + k * v.pixelStride, frame[src + 2 * k])
                    u.buffer.put(row * u.rowStride + k * u.pixelStride, frame[src + 2 * k + 1])
                }
            }
        }
    }

    /**
     * The last row of an interleaved plane stops one byte short — its final
     * sample is only in the [partner] plane's view — so that byte goes there.
     */
    private fun putRow(
        plane: Image.Plane, partner: Image.Plane?,
        row: Int, from: ByteArray, at: Int, count: Int
    ) {
        val b = plane.buffer
        val start = row * plane.rowStride
        val fits = minOf(count, b.capacity() - start)
        b.position(start)
        b.put(from, at, fits)
        if (fits < count) partner?.buffer?.let { it.put(it.capacity() - 1, from[at + count - 1]) }
    }

    /**
     * Whether the two semi-planar views alias one run: -1 when V leads (a
     * byte written at U's start shows at V's second), +1 when U leads, 0 when
     * they are separate buffers and must be written each on its own.
     */
    private fun interleaving(u: Image.Plane, v: Image.Plane): Int {
        val ub = u.buffer
        val vb = v.buffer
        if (ub.capacity() < 2 || vb.capacity() < 2) return 0
        return when {
            aliases(ub, vb) -> -1
            aliases(vb, ub) -> 1
            else -> 0
        }
    }

    /** Whether [a]'s first byte is [b]'s second — probed with two values, so a byte that merely held one cannot pass. */
    private fun aliases(a: ByteBuffer, b: ByteBuffer): Boolean {
        val keep = a.get(0)
        val same = listOf(0x5A, 0xA5).all { probe ->
            a.put(0, probe.toByte())
            b.get(1) == probe.toByte()
        }
        a.put(0, keep)
        return same
    }
}

/**
 * A picture already decoded for the screen — an MJPEG frame — drawn onto the
 * encoder's own input surface: a copy on the GPU, with the colour conversion
 * and the encoding left to the hardware, so a recording costs the stream no
 * second decode. A drawn picture carries only the moment it was posted, so
 * its time is smoothed on the way out.
 */
internal class BitmapEncoder(width: Int, height: Int) :
    H264Encoder(width, height, 0, surfaceInput = true) {

    private val whole = Rect(0, 0, width, height)

    fun encode(bitmap: Bitmap, sink: RecordSink) {
        val surface = inputSurface!!
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawBitmap(bitmap, null, whole, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        drain(sink, restamp = true)
    }
}

private fun ByteBuffer.bytes(): ByteArray = ByteArray(remaining()).also { duplicate().get(it) }

/**
 * Frame times for a source that sends none. A camera keeps a steady beat,
 * but its frames reach the recorder late by however long the work just
 * before took — the library's decode, the last encode, the network — and
 * stamped on arrival the recording played back in stutters: measured on a
 * steady 30 fps camera, steps from 5 to 73 ms. So each frame lands one beat
 * after the last. The beat starts at the camera's stated rate and follows its
 * measured pace; the grid leans a little toward the arrivals, so it cannot
 * wander off them; and a frame more than a few beats off is a real pause,
 * pinned where it arrived.
 */
internal class FrameClock(fps: Int) {
    private var beatUs = 1_000_000.0 / (if (fps > 0) fps else 30)
    private var lastUs = Long.MIN_VALUE
    private var lastArrivalUs = 0L

    fun stamp(arrivalUs: Long): Long {
        if (lastUs == Long.MIN_VALUE) {
            lastUs = arrivalUs
            lastArrivalUs = arrivalUs
            return arrivalUs
        }
        val gap = arrivalUs - lastArrivalUs
        lastArrivalUs = arrivalUs
        val next = lastUs + beatUs
        val drift = arrivalUs - next
        lastUs = if (kotlin.math.abs(drift) > 3 * beatUs) {
            maxOf(arrivalUs, lastUs + 1_000)
        } else {
            // only an ordinary gap teaches the pace; a pause says nothing of it
            if (gap > 0 && gap < 3 * beatUs) beatUs += (gap - beatUs) / 32
            (next + drift / 16).toLong()
        }
        return lastUs
    }
}
