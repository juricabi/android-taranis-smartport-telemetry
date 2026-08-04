package juricabi.com.telemetry.maps.maplibre

import android.content.Context
import android.os.Bundle
import juricabi.com.telemetry.maps.MapLine
import juricabi.com.telemetry.maps.MapMarker
import juricabi.com.telemetry.maps.MapWrapper
import juricabi.com.telemetry.maps.Position
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * The map, drawn by MapLibre.
 *
 * Beside OsmMapWrapper rather than instead of it, behind a setting, so the two
 * can be flown against each other on one flight before either is trusted.
 *
 * What it is for: osmdroid reprojects every point of every line on the UI
 * thread on every frame, and the marker's easing invalidates the map on every
 * frame, so a long flight costs milliseconds a frame before anything is drawn.
 * Here the geometry lives in the renderer and the cost stops depending on how
 * much of it there is.
 *
 * osmdroid was archived in November 2024 and 6.1.20 is the last release it will
 * ever have, so this has to happen eventually whatever the frame rate does.
 */
class MapLibreMapWrapper(
    private val context: Context,
    private val mapView: MapView,
    private val type: Int,
    private val onReady: () -> Unit
) : MapWrapper {

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var markerCount = 0
    private var lineCount = 0

    /**
     * Everything asked of the map before its style existed.
     *
     * The screen builds its lines and markers the moment it has a map, and a
     * MapLibre map has no style for a few frames after that — sources and
     * layers cannot be added until it does. Rather than have every caller learn
     * that, the work is kept here and run in order once the style lands.
     */
    private val pending = ArrayList<(Style) -> Unit>()

    private var cameraMoveListener: (() -> Unit)? = null
    private var orientationListener: ((Float) -> Unit)? = null

    /** The tile layer everything of ours is drawn above. */
    private val above = MapLibreStyles.topTileLayer(type)

    init {
        mapView.getMapAsync { ready ->
            map = ready
            ready.setStyle(MapLibreStyles.forType(type)) { loaded ->
                style = loaded
                val queued = ArrayList(pending)
                pending.clear()
                queued.forEach { it(loaded) }
                onReady()
            }
            // A hand on the map gives up neither following nor the chase, here
            // as everywhere else — the screen is told, and decides.
            ready.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    cameraMoveListener?.invoke()
                }
            }
            ready.addOnCameraMoveListener {
                orientationListener?.invoke(getMapOrientation())
            }
        }
    }

    internal fun whenReady(action: (Style) -> Unit) {
        val loaded = style
        if (loaded != null) action(loaded) else pending.add(action)
    }

    override fun initialized(): Boolean = style != null

    override var mapType: Int
        get() = type
        // The screen rebuilds the whole map for a change of type, which is how
        // osmdroid did it too: a style is chosen when it is built.
        set(value) {}

    override var isMyLocationEnabled: Boolean = false

    override fun getMyLocation(): Position? = null

    /**
     * Where the camera is put, every frame, with nothing eased here.
     *
     * The screen has already eased it — the position handed over is a share of
     * the way towards the last fix, worked out for this frame. osmdroid needed
     * its own glide on top because other things centred it too; this does not,
     * and adding one would only make the map lag the marker riding on it.
     */
    override fun moveCamera(position: Position) {
        map?.moveCamera(CameraUpdateFactory.newLatLng(LatLng(position.lat, position.lon)))
    }

    override fun moveCamera(position: Position, zoom: Float) {
        map?.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(position.lat, position.lon))
                    .zoom(zoom.toDouble())
                    .build()
            )
        )
    }

    override fun addMarker(icon: Int, color: Int, position: Position): MapMarker =
        MapLibreMarker(context, icon, color, position, "m${markerCount++}", above, ::whenReady)

    override fun addMarker(icon: Int, position: Position): MapMarker =
        MapLibreMarker(context, icon, null, position, "m${markerCount++}", above, ::whenReady)

    override fun addPolyline(width: Float, color: Int, vararg points: Position): MapLine {
        val line = MapLibreLine("l${lineCount++}", above, ::whenReady)
        line.addPoints(points.toList())
        line.color = color
        line.width = width * context.resources.displayMetrics.density
        return line
    }

    override fun addPolyline(color: Int): MapLine {
        val line = MapLibreLine("l${lineCount++}", above, ::whenReady)
        line.color = color
        return line
    }

    override fun setOnCameraMoveStartedListener(function: () -> Unit) {
        cameraMoveListener = function
    }

    override fun setOnOrientationChangedListener(listener: (Float) -> Unit) {
        orientationListener = listener
        listener(getMapOrientation())
    }

    /**
     * The angle the map is drawn at, in osmdroid's terms.
     *
     * MapLibre keeps a bearing — the compass direction the camera looks along —
     * and osmdroid keeps a rotation of the map itself. They are the same
     * quantity with opposite signs, and everything upstream speaks osmdroid's,
     * so the swap happens here and nowhere else.
     */
    override fun getMapOrientation(): Float = -(map?.cameraPosition?.bearing?.toFloat() ?: 0f)

    override fun setMapOrientation(degrees: Float) {
        val at = map?.cameraPosition ?: return
        map?.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(at).bearing(-degrees.toDouble()).build()
            )
        )
    }

    override fun resetMapOrientation() = setMapOrientation(0f)

    override fun getCentre(): Position {
        val at = map?.cameraPosition?.target ?: return Position(0.0, 0.0)
        return Position(at.latitude, at.longitude)
    }

    /** The renderer draws when it draws; there is nothing to invalidate. */
    override fun invalidate() {}

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        map?.setPadding(left, top, right, bottom)
    }

    override fun onCreate(bundle: Bundle?) = mapView.onCreate(bundle)
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onLowMemory() = mapView.onLowMemory()
    override fun onStart() = mapView.onStart()
    override fun onStop() = mapView.onStop()
    override fun onDestroy() = mapView.onDestroy()
    override fun onSaveInstanceState(outState: Bundle?) {
        if (outState != null) mapView.onSaveInstanceState(outState)
    }
}
