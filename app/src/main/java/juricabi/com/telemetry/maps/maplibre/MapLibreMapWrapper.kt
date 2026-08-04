package juricabi.com.telemetry.maps.maplibre

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
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
    private var lastReportedOrientation = Float.NaN

    /**
     * Where this phone is, and where it stood while a replay was recorded.
     *
     * Built before the style so the queue takes them first: everything is
     * layered above whatever went in before it, and these two belong under the
     * flight and its markers rather than over them.
     */
    /**
     * Where the phone stood first, where it is now second — and the order is
     * the point of it.
     *
     * Layers are stacked in the order they are made, so the second of these is
     * drawn over the first. Replaying a flight from the field it was flown at
     * stands the two on the same spot, and the one that is true of this moment
     * is the one worth seeing. The map has always settled it this way, by the
     * order of its two overlays; the ground view needed a depth bias to say the
     * same thing.
     */
    private val logged = MapLibreSpot(context, "logged", ::whenReady)
    private val me = MapLibreSpot(context, "me", ::whenReady)

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
            ready.addOnMapClickListener { at -> tapped(ready, at) }
            // A hand on the map wins, exactly as it does on the other map.
            //
            // The camera is gliding towards wherever the flight has got to, on
            // every frame, and left running it fights the finger: a drag
            // springs back, and a pinch or a turn is cancelled the moment it
            // starts, because a camera written programmatically ends whatever
            // gesture is in progress. So the glide is dropped while the map is
            // being touched — and picked up again by itself, since the frame
            // loop asks for the flight again on the very next frame.
            //
            // A touch listener rather than the camera-move-started callback:
            // that fires once when a gesture begins, and what is needed is for
            // the glide to stay out of the way for as long as the finger is
            // down. Returning false so the map still gets the gesture.
            mapView.setOnTouchListener { _, event ->
                // Down for as long as any finger is: a pointer going up while
                // another is still down is the end of a pinch, not the end of
                // the touch.
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_MOVE -> handOnMap = true
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> handOnMap = false
                }
                glideTo = null
                glideBearing = Double.NaN
                cameraMoveListener?.invoke()
                false
            }
            ready.addOnCameraMoveListener {
                // Only where the angle has really moved. This fires for any
                // camera move at all, and following the model is a camera move
                // on every frame — so the compass was being written sixty times
                // a second to say the same number, laying the readout out again
                // each time. osmdroid reports it when it changes and not
                // otherwise.
                val angle = getMapOrientation()
                if (angle != lastReportedOrientation) {
                    lastReportedOrientation = angle
                    orientationListener?.invoke(angle)
                }
            }
        }
    }

    /**
     * Run something against the style, once there is one — and never against
     * one that has been replaced.
     *
     * A style is only good while it is the map's current one. Change the map
     * type, or come back from the ground view, and the screen builds a new map
     * and takes the old one's lines and markers off first — each of which asks
     * the style it was made on to remove its layer. MapLibre throws for that
     * rather than ignoring it: "Calling removeLayer when a newer style is
     * loading/has loaded". It crashed the app on a switch, and again whenever a
     * Flightradar poll landed on one, which is why it looked occasional.
     *
     * Nothing is lost by dropping the work. A style that has been replaced is
     * not drawn any more, and taking layers off it is tidying something already
     * thrown away.
     */
    internal fun whenReady(action: (Style) -> Unit) {
        val loaded = style
        if (loaded == null) {
            pending.add(action)
            return
        }
        if (!loaded.isFullyLoaded) return
        action(loaded)
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

    /**
     * Where the camera is being taken, and the loop that takes it.
     *
     * The same shape the osmdroid map has had for a while, and it was the one
     * thing missing here. The screen hands over a position that is already
     * eased — a share of the way to the last fix, worked out for this frame —
     * and the camera eases towards *that* again. Two filters rather than one:
     * the model is drawn where the screen says and the ground under it moves at
     * its own, slower pace, which is what stops a following map from
     * shivering.
     *
     * Both the place and the angle are taken by the one loop and written in one
     * camera update. Written separately they are two moves a frame, and in
     * chase mode — where the angle changes as fast as the place — the second
     * was undoing part of the first.
     */
    private var glideTo: Position? = null
    private var glideBearing = Double.NaN
    private var gliding = false

    /** Whether a finger is on the map, in which case nothing else moves it. */
    private var handOnMap = false

    /** How much of what is left the camera takes each frame. */
    private val GLIDE = 0.25

    private val glide = object : Runnable {
        override fun run() {
            gliding = false
            val ready = map ?: return
            // Nothing is written to the camera while the map is being held.
            //
            // Dropping the targets on touch is not enough on its own: in chase
            // the map is turned to the model's heading on every frame, so the
            // glide is re-armed between one touch event and the next, and on a
            // replay wound on fast that is often enough to land inside a pinch
            // and end it. It comes back on its own — the frame loop asks for
            // the flight again as soon as the hand is off.
            if (handOnMap) return
            val at = ready.cameraPosition
            var lat = at.target?.latitude ?: 0.0
            var lon = at.target?.longitude ?: 0.0
            var bearing = at.bearing
            var again = false

            val to = glideTo
            if (to != null) {
                val dLat = to.lat - lat
                val dLon = to.lon - lon
                if (Math.abs(dLat) < 1e-7 && Math.abs(dLon) < 1e-7) {
                    lat = to.lat
                    lon = to.lon
                    glideTo = null
                } else {
                    lat += dLat * GLIDE
                    lon += dLon * GLIDE
                    again = true
                }
            }

            val turnTo = glideBearing
            if (!turnTo.isNaN()) {
                // the short way round, or a turn through north is a lap
                val turn = ((turnTo - bearing) % 360.0 + 540.0) % 360.0 - 180.0
                if (Math.abs(turn) < 0.05) {
                    bearing = turnTo
                    glideBearing = Double.NaN
                } else {
                    bearing += turn * GLIDE
                    again = true
                }
            }

            ready.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(at)
                        .target(LatLng(lat, lon))
                        .bearing(bearing)
                        .build()
                )
            )
            if (again) keepGliding()
        }
    }

    private fun keepGliding() {
        if (gliding) return
        gliding = true
        mapView.postOnAnimation(glide)
    }

    override fun moveCamera(position: Position) {
        if (map == null) {
            pendingTarget = position
            return
        }
        glideTo = position
        keepGliding()
    }

    override fun moveCamera(position: Position, zoom: Float) {
        val ready = map
        if (ready == null) {
            pendingTarget = position
            pendingZoom = zoom
            return
        }
        // asked for a place rather than followed to it, so it arrives
        glideTo = null
        ready.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(ready.cameraPosition)
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

    /**
     * The model's own marker, which everything else keeps under.
     *
     * The two ways of asking for a marker are not interchangeable here: one
     * takes the colour the model is drawn in and is the model, the other is the
     * traffic from Flightradar. Traffic is thrown away and made again on every
     * poll of it, so made the ordinary way it climbs above everything each time
     * — and the aircraft being flown disappears under an airliner passing
     * overhead, every half minute, for as long as one is in the sky.
     */
    private var modelLayer: String? = null

    /** Every marker on the map, by the name it puts on its own features. */
    private val markersById = HashMap<String, MapLibreMarker>()

    private fun remember(marker: MapLibreMarker): MapLibreMarker {
        markersById[marker.markerId] = marker
        return marker
    }

    override fun addMarker(icon: Int, color: Int, position: Position): MapMarker {
        val marker = MapLibreMarker(
            context, icon, color, position, "m${markerCount++}", null, ::whenReady
        ) { markersById.remove(it.markerId) }
        modelLayer = marker.layerName
        return remember(marker)
    }

    override fun addMarker(icon: Int, position: Position): MapMarker =
        remember(
            MapLibreMarker(
                context, icon, null, position, "m${markerCount++}", modelLayer, ::whenReady
            ) { markersById.remove(it.markerId) }
        )

    /**
     * What a tap on an aircraft says, the way the other map says it.
     *
     * osmdroid gives every marker a bubble and this map has none: the layer API
     * draws symbols and knows nothing about being tapped. So the tap is caught,
     * what is under the finger is asked for, and the words that marker is
     * carrying are put on the screen beside it.
     *
     * Only the Flightradar traffic has anything to say. Tapping the model, or
     * empty ground, closes whatever is open and does nothing else — which is
     * also why an empty bubble never appears, the fault osmdroid had to have
     * its own answer to.
     */
    private var bubble: PopupWindow? = null

    private fun dismissBubble() {
        bubble?.dismiss()
        bubble = null
    }

    private fun tapped(ready: MapLibreMap, at: LatLng): Boolean {
        dismissBubble()
        val talkative = markersById.values.filter { it.hasSomethingToSay() }
        if (talkative.isEmpty()) return false

        val density = context.resources.displayMetrics.density
        val point = ready.projection.toScreenLocation(at)
        // A box rather than the point itself: a finger is not a pixel, and an
        // aircraft icon at this size is a few dozen of them.
        val slop = 22f * density
        val box = RectF(point.x - slop, point.y - slop, point.x + slop, point.y + slop)
        val hits = ready.queryRenderedFeatures(box, *talkative.map { it.layerName }.toTypedArray())
        val name = hits.firstOrNull { it.hasProperty("marker") }
            ?.getStringProperty("marker") ?: return false
        val marker = markersById[name] ?: return false

        val words = if (marker.snippet.isEmpty()) marker.title
        else marker.title + "\n" + marker.snippet
        if (words.isEmpty()) return false

        val pad = (8 * density).toInt()
        val view = TextView(context)
        view.setPadding(pad, pad, pad, pad)
        view.setBackgroundColor(0xE6202020.toInt())
        view.setTextColor(Color.WHITE)
        view.textSize = 12f
        view.text = words

        val pop = PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        pop.isOutsideTouchable = true
        pop.isFocusable = false
        val where = IntArray(2)
        mapView.getLocationInWindow(where)
        pop.showAtLocation(
            mapView, Gravity.NO_GRAVITY,
            where[0] + point.x.toInt(), where[1] + point.y.toInt()
        )
        bubble = pop
        return true
    }

    override fun addPolyline(width: Float, color: Int, vararg points: Position): MapLine {
        val line = MapLibreLine("l${lineCount++}", ::whenReady)
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
        val line = MapLibreLine("l${lineCount++}", ::whenReady)
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
        if (map == null) {
            // Heading-up is applied as the map is built, and a map built
            // north-up stays north-up until the next heading arrives — which,
            // with a replay standing paused, is never.
            pendingOrientation = degrees
            return
        }
        // Eased by the same loop that carries the place, and in the same camera
        // update. A heading arrives many times a second and wanders a degree or
        // two on each; taken literally the map shakes.
        glideBearing = -degrees.toDouble()
        keepGliding()
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
    override fun onDestroy() {
        // a window of ours, which outlives the screen if nobody takes it down
        dismissBubble()
        mapView.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle?) {
        if (outState != null) mapView.onSaveInstanceState(outState)
    }
}
