package juricabi.com.telemetry.video

import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * One live picture, whatever carries it: a USB (UVC) receiver or goggles, or
 * an RTSP stream off the network.
 *
 * A source owns the view from start to stop, and only one is ever active. It
 * reports trouble through the callback it was built with rather than
 * throwing: the picture is optional equipment, and a missing camera must
 * never take the telemetry down with it.
 */
interface VideoSource {

    /** What a source tells the screen, from whatever thread it lives on. */
    interface Events {
        /**
         * The first real frame is showing — a renderer clearing a surface
         * does not count. The screen takes its waiting card down.
         */
        fun onLive()

        /**
         * The picture stopped but may return — a camera unplugged mid-watch.
         * The screen puts the waiting card back where the picture was, and
         * onLive takes it down again. Unlike onTrouble, this does not give
         * the watching up.
         */
        fun onIdle() {}

        /**
         * The source is dead. For a network stream the screen keeps the pane,
         * says what is wrong and retries; for USB it folds the picture away —
         * retrying there would storm the permission dialog.
         */
        fun onTrouble(what: String)

        /** Sound was dropped to keep the picture; the button should agree. */
        fun onAudioLost() {}
    }

    fun start(view: SurfaceView)
    fun stop()

    /**
     * Re-lays the current picture into the view — fit or fill, and the
     * remembered turn. The screen calls this when either button is tapped;
     * only the source knows the picture's size.
     */
    fun refit() {}

    /** Whether this source could play the stream's sound at all. */
    val hasAudio: Boolean get() = false

    /** The stream's sound, on or off. A no-op unless hasAudio. */
    fun setAudio(on: Boolean) {}
}

/**
 * Lay the picture into its half instead of stretching it over the whole
 * pane — a 4:3 receiver frame pulled over a 20:9 phone screen is not a
 * picture anyone can fly by.
 *
 * A SurfaceView cannot take a content matrix, and — the field taught this,
 * not the docs — its own view rotation does not turn the surface content
 * either: the decoder writes to a separate compositor layer the view's
 * transform never reaches, so setRotation only squished. The turn is
 * therefore done at the source — the decoder for UDP, the canvas for
 * MJPEG, the library for UVC — and this only sizes the surface to the box
 * the already-turned picture lies in, centred in its half and letterboxed:
 * the surface is never larger than its half, since a SurfaceView's overlay
 * cannot be clipped to it. The half is resized with the divider, which is
 * the zoom a fill button would have been.
 *
 * [videoWidth]/[videoHeight] are the picture's sides AS THEY LEAVE the
 * source, after any turn it has applied — so a source turning a 1280×720
 * feed sideways passes 720×1280. The half's size is read from the parent,
 * since the surface is now smaller than the pane and cannot report it.
 */
internal fun SurfaceView.fitPicture(videoWidth: Int, videoHeight: Int) {
    val half = parent as? ViewGroup ?: return
    val paneWidth = half.width
    val paneHeight = half.height
    if (videoWidth <= 0 || videoHeight <= 0 || paneWidth == 0 || paneHeight == 0) return
    val scale = minOf(paneWidth.toFloat() / videoWidth, paneHeight.toFloat() / videoHeight)
    val surfaceWidth = Math.round(videoWidth * scale)
    val surfaceHeight = Math.round(videoHeight * scale)
    val params = layoutParams as? FrameLayout.LayoutParams
        ?: FrameLayout.LayoutParams(surfaceWidth, surfaceHeight)
    if (params.width != surfaceWidth || params.height != surfaceHeight ||
        params.gravity != android.view.Gravity.CENTER
    ) {
        params.width = surfaceWidth
        params.height = surfaceHeight
        params.gravity = android.view.Gravity.CENTER
        layoutParams = params
    }
    // Pin the surface buffer to the same size. A SurfaceView's surface
    // does clip to its own bounds — but while a decoder renders into it,
    // a resize of the view alone left the surface buffer at its old, larger
    // size, drawn past the shrunk view and over the map. Fixing the buffer
    // makes the surface follow the view exactly.
    if (surfaceWidth > 0 && surfaceHeight > 0) holder.setFixedSize(surfaceWidth, surfaceHeight)
}

/** The picture's sides as they leave a source that turns [rotation]° at the
 *  source: a quarter-turn swaps them, so the half is fitted to the real box. */
internal fun turnedSides(videoWidth: Int, videoHeight: Int, rotation: Int): Pair<Int, Int> =
    if (rotation % 180 != 0) videoHeight to videoWidth else videoWidth to videoHeight
