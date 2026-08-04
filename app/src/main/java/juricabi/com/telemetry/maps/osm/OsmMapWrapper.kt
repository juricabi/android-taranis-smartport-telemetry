package juricabi.com.telemetry.maps.osm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import juricabi.com.telemetry.R
import android.os.Bundle
import android.preference.PreferenceManager
import juricabi.com.telemetry.maps.MapLine
import juricabi.com.telemetry.maps.MapMarker
import juricabi.com.telemetry.maps.MapWrapper
import juricabi.com.telemetry.maps.Position
import org.osmdroid.api.IMapController
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import android.location.Location


class OsmMapWrapper(private val context: Context, private val mapView: MapView, tileSource: OnlineTileSourceBase, private val callback: () -> Unit, private val overlayTileSources: List<OnlineTileSourceBase> = emptyList()) : MapWrapper {

    companion object {
        public const val MAP_TYPE_DEFAULT = 5
        public const val MAP_TYPE_TOPO = 6
        public const val MAP_TYPE_SATELLITE = 7
        public const val MAP_TYPE_SATELLITE_HYBRID = 8
    }

    private val compassLocationProvider = CompassLocationProvider()
    private val myLocationNewOverlay = MyLocationNewOverlay(compassLocationProvider, mapView)

    /** Where the phone stood while the flight being replayed was recorded. */
    private val loggedLocationProvider = CompassLocationProvider()
    private val loggedLocationOverlay = MyLocationNewOverlay(loggedLocationProvider, mapView)
    private val markers = mutableListOf<OsmMarker>()

    init {
        Configuration.getInstance().load(
            context, PreferenceManager.getDefaultSharedPreferences(
                context
            )
        )
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.setMultiTouchControls(true)
        mapView.setTileSource(tileSource)
        // do not let the user zoom past the imagery, or the map goes blank
        var maxZoom = tileSource.maximumZoomLevel
        for (overlay in overlayTileSources) {
            if (overlay.maximumZoomLevel < maxZoom) maxZoom = overlay.maximumZoomLevel
        }
        // Allow two levels past the imagery: osmdroid upscales the deepest real
        // tiles rather than drawing nothing, so it goes blurry instead of blank.
        mapView.setMaxZoomLevel((maxZoom + 2).toDouble())
        mapView.setMinZoomLevel(tileSource.minimumZoomLevel.toDouble())
        // Tiles that are still loading, or that do not exist at this zoom, are
        // painted with the loading placeholder. Left at its default that is a
        // white grid, which flashes as white blocks while zooming. Draw nothing
        // instead and let the dark map background show through.
        mapView.overlayManager.tilesOverlay.loadingBackgroundColor =
            android.graphics.Color.TRANSPARENT
        mapView.overlayManager.tilesOverlay.loadingLineColor =
            android.graphics.Color.TRANSPARENT
        mapView.setBackgroundColor(android.graphics.Color.rgb(28, 28, 28))
        for (overlayTileSource in overlayTileSources) {
            val overlayProvider = MapTileProviderBasic(context, overlayTileSource)
            val tilesOverlay = TilesOverlay(overlayProvider, context)
            tilesOverlay.loadingBackgroundColor = android.graphics.Color.TRANSPARENT
            tilesOverlay.loadingLineColor = android.graphics.Color.TRANSPARENT
            mapView.overlayManager.add(tilesOverlay)
        }
        mapView.overlayManager.add(DeadbandRotationGestureOverlay(mapView))
        setArrowColours(
            context.resources.getColor(R.color.colorPosArrow),
            context.resources.getColor(R.color.colorPosArrowLogged)
        )
        // The arrow, and nothing where there is no arrow to draw.
        //
        // The map draws one of two things: the arrow when it knows which way
        // the phone is facing, and a plain dot when it does not. The dot is a
        // moment at the start before the compass has read anything, and a
        // second thing to look at for no gain — the ring already says where the
        // phone is and how well it is known.
        mapView.overlayManager.add(myLocationNewOverlay)
        // The same arrow in orange, for the place a recording says the phone
        // stood. Under the live one, so where the two land on top of each other
        // — a replay of a flight watched from where it is being watched now —
        // the one that is true of this moment is the one on top.
        loggedLocationOverlay.disableMyLocation()
        mapView.overlayManager.add(mapView.overlayManager.size - 1, loggedLocationOverlay)
        val mapController: IMapController = mapView.controller
        mapController.setZoom(4.toDouble())
        callback()
    }

    /**
     * The two arrows, in whatever colours have been chosen.
     *
     * Drawn here rather than tinted from a drawable: the arrow is a bright
     * shape with a dark edge, and a tint takes the edge with it — which is what
     * keeps it legible against satellite imagery.
     */
    override fun setArrowColours(live: Int, logged: Int) {
        myLocationNewOverlay.setDirectionArrow(nothing(), arrowBitmap(live, 26))
        loggedLocationOverlay.setDirectionArrow(nothing(), arrowBitmap(logged, 26))
        mapView.invalidate()
    }

    /** What is drawn where the phone's bearing is not known: nothing. */
    private fun nothing(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    private fun arrowBitmap(color: Int, dp: Int): Bitmap {
        val px = (context.resources.displayMetrics.density * dp).toInt()
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val u = px / 24f
        val path = android.graphics.Path()
        path.moveTo(12f * u, 3f * u)
        path.lineTo(18.5f * u, 20f * u)
        path.lineTo(12f * u, 16.2f * u)
        path.lineTo(5.5f * u, 20f * u)
        path.close()
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        fill.style = android.graphics.Paint.Style.FILL
        fill.color = color
        canvas.drawPath(path, fill)
        val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        edge.style = android.graphics.Paint.Style.STROKE
        edge.strokeWidth = 1.6f * u
        edge.strokeJoin = android.graphics.Paint.Join.ROUND
        // a dark edge of the arrow's own colour, so a pale arrow keeps a pale
        // outline and a deep one a deep outline, and both read on any ground
        edge.color = android.graphics.Color.argb(
            204,
            android.graphics.Color.red(color) / 5,
            android.graphics.Color.green(color) / 5,
            android.graphics.Color.blue(color) / 5
        )
        canvas.drawPath(path, edge)
        return bitmap
    }

    private fun bitmapFrom(resId: Int, dp: Int): Bitmap {
        val d = context.resources.getDrawable(resId).mutate()
        var px = (context.resources.displayMetrics.density * dp).toInt()
        if (px < 1) px = 1
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(bmp))
        return bmp
    }

    override fun initialized() : Boolean {
        return true;
    }

    override fun setPhoneBearing(degrees: Float) {
        compassLocationProvider.setBearing(degrees)
    }

    override fun setPhoneLocation(position: Position, accuracy: Float) {
        compassLocationProvider.setLocation(placeAt(position, accuracy, Float.NaN))
    }

    private fun placeAt(position: Position, accuracy: Float, heading: Float): Location {
        val at = Location("phone")
        at.latitude = position.lat
        at.longitude = position.lon
        if (!accuracy.isNaN() && accuracy > 0f) at.accuracy = accuracy
        // a bearing at all is what makes the map draw the arrow rather than
        // nothing, so an unknown one is left unset
        if (!heading.isNaN()) at.bearing = heading
        return at
    }

    override fun showRecordedLocation(position: Position?, accuracy: Float, heading: Float) {
        if (position == null) {
            loggedLocationProvider.setLocation(null)
            loggedLocationOverlay.disableMyLocation()
            return
        }
        // only where it is off: turning it on takes the provider round from the
        // start, which while a seek bar is dragged is many times a second
        if (!loggedLocationOverlay.isMyLocationEnabled) loggedLocationOverlay.enableMyLocation()
        loggedLocationProvider.setLocation(placeAt(position, accuracy, heading))
    }

    override fun getMyLocation(): Position? {
        val loc = myLocationNewOverlay.myLocation ?: return null
        return Position(loc.latitude, loc.longitude)
    }

    override var mapType: Int
        get() = 0
        set(value) {}
    override var isMyLocationEnabled: Boolean
        get() = myLocationNewOverlay.isMyLocationEnabled
        set(value) {
            if (value) {
                myLocationNewOverlay.enableMyLocation()
            } else {
                myLocationNewOverlay.disableMyLocation()
            }
        }

    /**
     * Where the map is being asked to look, and the loop that takes it there.
     *
     * A fix lands five or ten times a second and the screen draws sixty, so
     * centring the map the moment one arrives makes it step. osmdroid's own
     * animation is worse here: each call builds another animator and never
     * stops the last, so ten of them end up writing the centre at once.
     *
     * One target, one loop, a quarter of the way there each frame. It is always
     * moving, always less than a fix behind, and it cannot fight itself.
     */
    private var glideTo: GeoPoint? = null
    private var glideTurn = Float.NaN
    private var gliding = false

    private val glide = object : Runnable {
        override fun run() {
            var again = false
            val to = glideTo
            if (to != null) {
                val at = mapView.mapCenter
                val dLat = to.latitude - at.latitude
                val dLon = to.longitude - at.longitude
                if (Math.abs(dLat) < 1e-7 && Math.abs(dLon) < 1e-7) {
                    mapView.controller.setCenter(to)
                    glideTo = null
                } else {
                    mapView.controller.setCenter(
                        GeoPoint(at.latitude + dLat * 0.25, at.longitude + dLon * 0.25))
                    again = true
                }
            }
            val turnTo = glideTurn
            if (!turnTo.isNaN()) {
                // Twice round, because a remainder in Kotlin keeps the sign of
                // what went into it: a target of minus three hundred and an
                // orientation of plus three hundred gave a turn of minus two
                // hundred and ninety where it should have been seventy, and the
                // map span most of the way round instead of a little way back.
                var turn = ((turnTo - mapView.mapOrientation) % 360f + 540f) % 360f - 180f
                if (Math.abs(turn) < 0.05f) {
                    turnMapTo(turnTo)
                    glideTurn = Float.NaN
                } else {
                    turnMapTo(mapView.mapOrientation + turn * 0.25f)
                    again = true
                }
            }
            gliding = again
            if (again) mapView.postOnAnimation(this)
        }
    }

    private fun startGliding() {
        if (gliding) return
        gliding = true
        mapView.postOnAnimation(glide)
    }

    /**
     * Turning without a layout pass.
     *
     * The one-argument setter asks the whole view tree to measure and lay out
     * again, and in heading-up that happens on every heading the model sends.
     * The map re-reads its own angle at the start of every draw, so an
     * invalidate is all it needs.
     */
    private fun turnMapTo(degrees: Float) {
        mapView.setMapOrientation(degrees, false)
        markers.forEach { m -> m.updateForMapOrientation() }
        onOrientationChangedListener?.invoke(degrees)
        mapView.invalidate()
    }

    override fun moveCamera(position: Position) {
        glideTo = position.toGeoPoint()
        startGliding()
    }

    override fun moveCamera(position: Position, zoom: Float) {
        // asked for a place rather than followed to it, so it arrives
        glideTo = null
        mapView.controller.setZoom(zoom.toDouble())  //set zoom first, center second
        mapView.controller.setCenter(position.toGeoPoint())
    }

    override fun addMarker(icon: Int, color: Int, position: Position): MapMarker {
        val marker = OsmMarker(icon, color, position, mapView, context)
        markers.add(marker)
        return marker
    }

    override fun addMarker(icon: Int, position: Position): MapMarker {
        val marker = OsmMarker(icon, null, position, mapView, context)
        markers.add(marker)
        return marker
    }

    override fun addPolyline(width: Float, color: Int, vararg points: Position): MapLine {
        val osmLine = OsmLine(mapView)
        osmLine.addPoints(points.toList())
        osmLine.color = color
        // The width was taken and thrown away, so the line home, the line ahead
        // and the flight plans were all drawn at whatever osmdroid's default
        // happened to be — while the ground view drew each at the width it was
        // given, and the two views disagreed about which lines were heavier.
        osmLine.width = width * context.resources.displayMetrics.density
        return osmLine
    }

    private var onCameraMoveListener: (() -> Unit)? = null
    private var onOrientationChangedListener: ((Float) -> Unit)? = null
    private var lastReportedOrientation: Float = 0f

    override fun setOnCameraMoveStartedListener(function: () -> Unit) {
        onCameraMoveListener = function
        mapView.setOnTouchListener { v, event ->
            // A hand on the map wins. The camera glides towards where the model
            // was last seen, and without this it went on gliding there under
            // the finger — every drag springing back, however plainly the
            // following had been turned off.
            glideTo = null
            glideTurn = Float.NaN
            onCameraMoveListener?.invoke()
            markers.forEach { it.updateForMapOrientation() }
            val orientation = mapView.mapOrientation
            if (orientation != lastReportedOrientation) {
                lastReportedOrientation = orientation
                onOrientationChangedListener?.invoke(orientation)
            }
            return@setOnTouchListener false
        }
    }

    override fun setOnOrientationChangedListener(listener: (Float) -> Unit) {
        onOrientationChangedListener = listener
        listener(mapView.mapOrientation)
    }

    override fun addPolyline(color: Int): MapLine {
        val res = OsmLine(mapView)
        res.color = color;
        return res;
    }

    override fun onCreate(bundle: Bundle?) {
    }

    override fun onResume() {
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
    }

    override fun onLowMemory() {
    }

    override fun onStart() {
    }

    override fun onStop() {
    }

    override fun onDestroy() {
        // osmdroid's own teardown: without it the tile provider's threads and
        // its cache outlive the view, and this view is thrown away and built
        // again every time the map and the ground swap places.
        compassLocationProvider.destroy()
        loggedLocationProvider.destroy()
        mapView.onDetach()
    }

    override fun onSaveInstanceState(outState: Bundle?) {
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
    }

    override fun getMapOrientation(): Float {
        return mapView.mapOrientation
    }

    override fun getCentre(): Position {
        val c = mapView.mapCenter
        return Position(c.latitude, c.longitude)
    }

    private var orientationAnimator: ValueAnimator? = null

    override fun resetMapOrientation() {
        orientationAnimator?.cancel()
        glideTurn = Float.NaN
        var start = mapView.mapOrientation % 360f
        if (start > 180f) start -= 360f
        if (start < -180f) start += 360f
        orientationAnimator = ValueAnimator.ofFloat(start, 0f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val angle = it.animatedValue as Float
                mapView.mapOrientation = angle
                markers.forEach { m -> m.updateForMapOrientation() }
                onOrientationChangedListener?.invoke(angle)
            }
            start()
        }
    }

    /**
     * Turned towards an angle by the same loop that follows the model, so the
     * rate is the screen's and not the telemetry's.
     *
     * Easing it as each heading arrived meant the map turned in one step per
     * message — five differently sized jerks a second, and a link at half the
     * rate took twice as long to come round.
     */
    override fun setMapOrientation(degrees: Float) {
        orientationAnimator?.cancel()
        glideTurn = degrees
        startGliding()
    }

    override fun invalidate() {
        this.mapView.invalidate()
    }

}