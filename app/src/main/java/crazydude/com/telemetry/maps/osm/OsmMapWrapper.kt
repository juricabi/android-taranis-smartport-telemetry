package crazydude.com.telemetry.maps.osm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import crazydude.com.telemetry.R
import android.os.Bundle
import android.preference.PreferenceManager
import crazydude.com.telemetry.maps.MapLine
import crazydude.com.telemetry.maps.MapMarker
import crazydude.com.telemetry.maps.MapWrapper
import crazydude.com.telemetry.maps.Position
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
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay


class OsmMapWrapper(private val context: Context, private val mapView: MapView, tileSource: OnlineTileSourceBase, private val callback: () -> Unit, private val overlayTileSources: List<OnlineTileSourceBase> = emptyList()) : MapWrapper {

    companion object {
        public const val MAP_TYPE_DEFAULT = 5
        public const val MAP_TYPE_TOPO = 6
        public const val MAP_TYPE_SATELLITE = 7
        public const val MAP_TYPE_SATELLITE_HYBRID = 8
    }

    private val compassLocationProvider = CompassLocationProvider(context)
    private val myLocationNewOverlay = MyLocationNewOverlay(compassLocationProvider, mapView)
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
        for (overlayTileSource in overlayTileSources) {
            val overlayProvider = MapTileProviderBasic(context, overlayTileSource)
            val tilesOverlay = TilesOverlay(overlayProvider, context)
            tilesOverlay.loadingBackgroundColor = android.graphics.Color.TRANSPARENT
            tilesOverlay.loadingLineColor = android.graphics.Color.TRANSPARENT
            mapView.overlayManager.add(tilesOverlay)
        }
        mapView.overlayManager.add(DeadbandRotationGestureOverlay(mapView))
        myLocationNewOverlay.setDirectionArrow(
            bitmapFrom(R.drawable.ic_pos_dot, 16),
            bitmapFrom(R.drawable.ic_pos_arrow, 22)
        )
        mapView.overlayManager.add(myLocationNewOverlay)
        val mapController: IMapController = mapView.controller
        mapController.setZoom(4.toDouble())
        callback()
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

    override fun moveCamera(position: Position) {
        mapView.controller.setCenter(position.toGeoPoint())
    }

    override fun moveCamera(position: Position, zoom: Float) {
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
        osmLine.color = color;
        return osmLine
    }

    private var onCameraMoveListener: (() -> Unit)? = null
    private var onOrientationChangedListener: ((Float) -> Unit)? = null
    private var lastReportedOrientation: Float = 0f

    override fun setOnCameraMoveStartedListener(function: () -> Unit) {
        onCameraMoveListener = function
        mapView.setOnTouchListener { v, event ->
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

    fun setOnOrientationChangedListener(listener: (Float) -> Unit) {
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
    }

    override fun onSaveInstanceState(outState: Bundle?) {
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
    }

    override fun getMapOrientation(): Float {
        return mapView.mapOrientation
    }

    private var orientationAnimator: ValueAnimator? = null

    override fun resetMapOrientation() {
        orientationAnimator?.cancel()
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

    override fun invalidate() {
        this.mapView.invalidate()
    }

}