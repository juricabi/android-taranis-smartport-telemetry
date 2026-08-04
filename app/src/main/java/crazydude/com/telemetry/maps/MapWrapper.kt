package crazydude.com.telemetry.maps

import android.os.Bundle

interface MapWrapper {
    var mapType: Int
    var isMyLocationEnabled: Boolean

    fun initialized() : Boolean
    fun getMyLocation(): Position?

    /**
     * Draw the phone where it was, from a recording, rather than where it is.
     *
     * Null hands it back to the live one. Anything that cannot do this draws
     * the live arrow as it always did.
     */
    fun showRecordedLocation(position: Position?, accuracy: Float, heading: Float) {}

    /** The colours of the two position arrows: where the phone is, and where it was. */
    fun setArrowColours(live: Int, logged: Int) {}

    /** Which way this phone is facing, read once by the screen. */
    fun setPhoneBearing(degrees: Float) {}

    /** Where this phone is, from the one listener the screen keeps. */
    fun setPhoneLocation(position: Position, accuracy: Float) {}

    fun moveCamera(position: Position)
    fun moveCamera(position: Position, zoom: Float)
    fun addMarker(icon: Int, color: Int, position: Position): MapMarker
    fun addMarker(icon: Int, position: Position): MapMarker
    fun addPolyline(width: Float, color: Int, vararg points: Position): MapLine
    fun setOnCameraMoveStartedListener(function: () -> Unit)
    fun addPolyline(color: Int): MapLine

    fun getMapOrientation(): Float

    /** Where the map is looking now, which after a drag is where the hand left it. */
    fun getCentre(): Position
    fun resetMapOrientation()

    /** Turn the map to an angle and stay there, for heading up. */
    fun setMapOrientation(degrees: Float)
    fun invalidate()

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