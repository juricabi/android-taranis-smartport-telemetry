package juricabi.com.telemetry.maps

import android.os.Bundle

/**
 * The 2D map as the flight sees it. One adapter satisfies this today —
 * MapLibre — and every member is abstract on purpose: the default bodies
 * that used to live here kept behaviour for the Google and osmdroid maps,
 * both long deleted, and each default was a case a reader had to disprove.
 */
interface MapWrapper {
    var isMyLocationEnabled: Boolean

    fun initialized() : Boolean
    fun getMyLocation(): Position?

    /**
     * Draw the phone where it was, from a recording, rather than where it is.
     *
     * Null hands it back to the live one.
     */
    fun showRecordedLocation(position: Position?, accuracy: Float, heading: Float)

    /** The colours of the two position arrows: where the phone is, and where it was. */
    fun setArrowColours(live: Int, logged: Int)

    /** Which way this phone is facing, read once by the screen. */
    fun setPhoneBearing(degrees: Float)

    /** Where this phone is, from the one listener the screen keeps. */
    fun setPhoneLocation(position: Position, accuracy: Float)

    fun moveCamera(position: Position)
    fun moveCamera(position: Position, zoom: Float)

    /** The same journey, flown: for buttons, where arrival is watchable. */
    fun flyTo(position: Position, zoom: Float)

    /**
     * Put the camera at a position already eased by the caller.
     *
     * The orientation is in the same sense as [setMapOrientation], or NaN to
     * leave it unchanged.
     */
    fun moveCameraNow(position: Position, orientationDegrees: Float)

    /**
     * Publish the moving things that were staged during this display tick.
     *
     * The renderer owns several objects and exposes them as one immutable
     * frame, so its render thread can never catch the model after its lines
     * (or vice versa).
     */
    fun commitVisualFrame()

    fun addMarker(icon: Int, color: Int, position: Position): MapMarker
    fun addMarker(icon: Int, position: Position): MapMarker
    fun addPolyline(width: Float, color: Int, vararg points: Position): MapLine

    /** The recorded flight, named separately so other layers can stay above it. */
    fun addFlightLine(width: Float, color: Int): MapLine

    /** The small, moving end of the recorded flight, over its settled history. */
    fun addFlightHeadLine(width: Float, color: Int): MapLine

    /** A planned flight, kept in the same band below the line home. */
    fun addFlightPlanLine(width: Float, color: Int, vararg points: Position): MapLine

    /** The line home, kept above the recorded flight and below the model. */
    fun addHomeLine(width: Float, color: Int): MapLine

    /** The operator line, playback only: to where they stood, then. */
    fun addOperatorLine(width: Float, color: Int): MapLine
    fun setOnCameraMoveStartedListener(function: () -> Unit)

    /**
     * The angle the map has been turned to, as it changes, for the compass.
     *
     * On the interface rather than on each map, so the screen can set one up
     * without knowing which of them it was handed.
     */
    fun setOnOrientationChangedListener(listener: (Float) -> Unit)

    fun addPolyline(color: Int): MapLine

    fun getMapOrientation(): Float

    /** Where the map is looking now, which after a drag is where the hand left it. */
    fun getCentre(): Position
    fun resetMapOrientation()

    /** Turn the map to an angle and stay there, for heading up. */
    fun setMapOrientation(degrees: Float)

    fun onCreate(bundle: Bundle?)
    fun onResume()
    fun onPause()
    fun onLowMemory()
    fun onStart()
    fun onStop()
    fun onDestroy()
    fun onSaveInstanceState(outState: Bundle?)
    fun setPadding(left: Int, top: Int, right: Int, bottom: Int)
}
