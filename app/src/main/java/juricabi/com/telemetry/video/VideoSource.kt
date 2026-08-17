package juricabi.com.telemetry.video

import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import juricabi.com.telemetry.manager.PreferenceManager

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
 * A SurfaceView cannot take a content matrix the way a TextureView could,
 * so the same three choices are made by moving the view itself: the surface
 * is sized to the picture's on-screen box and centred in its half, and the
 * turn is the view's own rotation. Letterbox sizes the surface inside the
 * half; fill sizes it to overflow and the half clips the surplus. Every
 * source funnels every size and layout change through this, on the UI
 * thread — layout params and rotation belong to it.
 *
 * The half's size is read from the parent, since the surface is now smaller
 * than the pane and cannot report it. Rotation follows the view (API 24+);
 * below that the surface does not turn, an accepted graceful loss for a
 * device population that has all but vanished.
 */
internal fun SurfaceView.fitPicture(videoWidth: Int, videoHeight: Int) {
    val half = parent as? ViewGroup ?: return
    val paneWidth = half.width
    val paneHeight = half.height
    if (videoWidth <= 0 || videoHeight <= 0 || paneWidth == 0 || paneHeight == 0) return
    val preferences = PreferenceManager(context)
    val rotation = preferences.getVideoRotation()
    val fill = preferences.isVideoFillEnabled()
    // the picture's sides as they will lie on screen after the turn
    val sideways = rotation % 180 != 0
    val shownWidth = if (sideways) videoHeight else videoWidth
    val shownHeight = if (sideways) videoWidth else videoHeight
    val scale =
        if (fill) maxOf(paneWidth.toFloat() / shownWidth, paneHeight.toFloat() / shownHeight)
        else minOf(paneWidth.toFloat() / shownWidth, paneHeight.toFloat() / shownHeight)
    // The box as it lies on screen, then un-turned back to how the surface
    // is laid out before its own rotation swings it there: a quarter-turn
    // rotates around the centre, so the pre-rotation box has the sides
    // swapped.
    val onScreenWidth = Math.round(shownWidth * scale)
    val onScreenHeight = Math.round(shownHeight * scale)
    val boxWidth = if (sideways) onScreenHeight else onScreenWidth
    val boxHeight = if (sideways) onScreenWidth else onScreenHeight
    rotation.toFloat().let { if (this.rotation != it) this.rotation = it }
    val params = layoutParams as? FrameLayout.LayoutParams
        ?: FrameLayout.LayoutParams(boxWidth, boxHeight)
    if (params.width != boxWidth || params.height != boxHeight ||
        params.gravity != android.view.Gravity.CENTER
    ) {
        params.width = boxWidth
        params.height = boxHeight
        params.gravity = android.view.Gravity.CENTER
        layoutParams = params
    }
}
