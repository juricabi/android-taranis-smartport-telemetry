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

    /**
     * Where this phone is, and where it stood while a replay was recorded.
     *
     * Built before the style so the queue takes them first: everything is
     * layered above whatever went in before it, and these two belong under the
     * flight and its markers rather than over them.
     */
    private val me = MapLibreSpot(context, "me", above, ::whenReady)
    private val logged = MapLibreSpot(context, "logged", above, ::whenReady)

    init {
        mapView.getMapAsync { ready ->
            map = ready
            // MapLibre brings its own compass and its own badge. This screen
            // already draws a compass of its own, in the place it has always
            // been, and a second one disagreeing with it in the corner is worse
            // than none.
            ready.uiSettings.isCompassEnabled = false
            ready.uiSettings.isLogoEnabled = false
            ready.uiSettings.isAttributionEnabled = false
            // Two fingers dragged up tilts a MapLibre map, and this one has no
            // height in it to tilt: the ground stays flat and the imagery
            // stretches away, which looks like a fault and is one. Leaning over
            // a landscape is what the ground view is for, and it has real
            // elevation under it. Turning is kept — heading-up needs it.
            ready.uiSettings.isTiltGesturesEnabled = false
            // Two levels past the last real pictures, which osmdroid allowed
            // too: past that the ground is upscaled mush and the flight is
            // being read off nothing. Left at MapLibre's own ceiling a pinch
            // runs to twenty-five and the map is a colour.
            ready.setMaxZoomPreference(
                cameraZoom(MapLibreStyles.maxTileZoom(type) + 2f)
            )
            ready.setMinZoomPreference(0.0)
            // and wherever it was pointed while it was still being built —
            // after the limits, so the opening zoom is clamped by them rather
            // than clamping them
            applyPendingCamera()
            pendingPadding?.let {
                ready.setPadding(it[0], it[1], it[2], it[3])
                pendingPadding = null
            }
            pendingOrientation?.let {
                setMapOrientation(it)
                pendingOrientation = null
            }
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

    override var isMyLocationEnabled: Boolean = true
        set(value) {
            field = value
            me.setVisible(value)
        }

    /**
     * Where the phone is, as this map was last told.
     *
     * osmdroid answered this from the location provider inside its own overlay.
     * There is no provider here and there should not be: DataService owns the
     * only location listener there is, because it outlives the screen, and the
     * screen hands what it hears to whichever map it has. Returning nothing
     * from here left the locate button saying the phone had no location while
     * the arrow for it was on the screen, and took the bearing off find my
     * quad's "from you" as well.
     */
    override fun getMyLocation(): Position? = me.position()

    override fun setPhoneLocation(position: Position, accuracy: Float) {
        me.place(position, accuracy, phoneBearing)
    }

    private var phoneBearing = Float.NaN

    override fun setPhoneBearing(degrees: Float) {
        phoneBearing = degrees
        me.place(me.position(), me.accuracy(), degrees)
    }

    override fun setArrowColours(live: Int, logged: Int) {
        me.setColour(live)
        this.logged.setColour(logged)
    }

    override fun showRecordedLocation(position: Position?, accuracy: Float, heading: Float) {
        logged.place(position, accuracy, heading)
    }

    /**
     * Where the camera is put, every frame, with nothing eased here.
     *
     * The screen has already eased it — the position handed over is a share of
     * the way towards the last fix, worked out for this frame. osmdroid needed
     * its own glide on top because other things centred it too; this does not,
     * and adding one would only make the map lag the marker riding on it.
     */
    /**
     * Where the camera was asked to look before there was a camera.
     *
     * The screen points the map at the model the moment it has built one, and
     * a MapLibre map does not exist yet at that moment — getMapAsync has not
     * come back. Every one of those calls went into a null and was lost, so the
     * map opened looking at the whole world from zoom four wherever it was
     * pointed. osmdroid's view is usable the instant it is constructed and
     * never had the problem.
     *
     * The two are kept apart on purpose: the frame loop asks for a place
     * without a zoom many times a second, and it must not be able to throw away
     * the zoom the opening call asked for.
     */
    private var pendingTarget: Position? = null
    private var pendingZoom: Float? = null

    private fun applyPendingCamera() {
        val target = pendingTarget ?: return
        val zoom = pendingZoom
        pendingTarget = null
        pendingZoom = null
        if (zoom != null) moveCamera(target, zoom) else moveCamera(target)
    }

    override fun moveCamera(position: Position) {
        val ready = map
        if (ready == null) {
            pendingTarget = position
            return
        }
        ready.moveCamera(CameraUpdateFactory.newLatLng(LatLng(position.lat, position.lon)))
    }

    override fun moveCamera(position: Position, zoom: Float) {
        val ready = map
        if (ready == null) {
            pendingTarget = position
            pendingZoom = zoom
            return
        }
        ready.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(position.lat, position.lon))
                    .zoom(cameraZoom(zoom))
                    .build()
            )
        )
    }

    /**
     * A zoom level in osmdroid's terms, in MapLibre's.
     *
     * They are not the same number. MapLibre's zoom is defined against 512
     * pixel tiles and every tile server here serves 256 pixel ones, so the same
     * ground scale is one level lower: MapLibre 17 shows what osmdroid calls
     * 18. Handed straight across, everything sat one level deeper than it was
     * asked for — which is also one level past the last pictures there are, so
     * the ground went white.
     *
     * Everything upstream speaks osmdroid's, as it does for orientation, and
     * the swap happens here.
     */
    private fun cameraZoom(osmdroidZoom: Float): Double = osmdroidZoom.toDouble() - 1.0

    override fun addMarker(icon: Int, color: Int, position: Position): MapMarker =
        MapLibreMarker(context, icon, color, position, "m${markerCount++}", above, ::whenReady)

    override fun addMarker(icon: Int, position: Position): MapMarker =
        MapLibreMarker(context, icon, null, position, "m${markerCount++}", above, ::whenReady)

    override fun addPolyline(width: Float, color: Int, vararg points: Position): MapLine {
        val line = MapLibreLine("l${lineCount++}", above, ::whenReady)
        line.addPoints(points.toList())
        line.color = color
        // Not scaled by the display's density, which is what osmdroid needs:
        // it paints in real pixels, MapLibre takes a width already independent
        // of them. Multiplied here as well, a three pixel heading line came out
        // at eight on any modern screen.
        line.width = width
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

    private var pendingOrientation: Float? = null

    override fun setMapOrientation(degrees: Float) {
        val at = map?.cameraPosition ?: run {
            // Heading-up is applied as the map is built, and a map built
            // north-up stays north-up until the next heading arrives — which,
            // with a replay standing paused, is never.
            pendingOrientation = degrees
            return
        }
        map?.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(at).bearing(-degrees.toDouble()).build()
            )
        )
    }

    override fun resetMapOrientation() = setMapOrientation(0f)

    /**
     * Where the map is looking, and never a made-up answer.
     *
     * Nothing off the coast of Africa: this is subtracted from where the model
     * is to work out how far the map has been dragged away from it, and a zero
     * here is a lean of the whole width of the world — the camera would be sent
     * somewhere it could never come back from. Before there is a camera, the
     * honest answer is wherever it has been asked to look.
     */
    override fun getCentre(): Position {
        val at = map?.cameraPosition?.target
            ?: return pendingTarget ?: Position(0.0, 0.0)
        return Position(at.latitude, at.longitude)
    }

    /** The renderer draws when it draws; there is nothing to invalidate. */
    override fun invalidate() {}

    private var pendingPadding: IntArray? = null

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        val ready = map
        if (ready == null) {
            // Set once while the screen is laid out, which is before this map
            // exists — dropped, the model sits under the readouts rather than
            // in the space left for it.
            pendingPadding = intArrayOf(left, top, right, bottom)
            return
        }
        ready.setPadding(left, top, right, bottom)
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
