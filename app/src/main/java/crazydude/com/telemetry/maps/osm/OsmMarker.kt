package crazydude.com.telemetry.maps.osm

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.core.graphics.drawable.DrawableCompat
import crazydude.com.telemetry.R
import crazydude.com.telemetry.maps.MapMarker
import crazydude.com.telemetry.maps.Position
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class OsmMarker(icon: Int, color: Int?, position: Position, private val mapView: MapView, private val context: Context) : MapMarker {

    private val marker = Marker(mapView)
    private var heading: Float = 0f

    private fun buildIcon(icon: Int, color: Int?): Drawable {
        val body = context.resources.getDrawable(icon).mutate()
        if (color != null) DrawableCompat.setTint(body, color)
        if (icon != R.drawable.ic_plane) return body
        // dark silhouette underneath, so a light marker stays readable on satellite imagery
        val outline = context.resources.getDrawable(R.drawable.ic_plane_outline).mutate()
        return LayerDrawable(arrayOf(outline, body))
    }

    init {
        marker.icon = buildIcon(icon, color)
        marker.position = position.toGeoPoint()
        mapView.overlayManager.add(marker)
    }

    fun updateForMapOrientation() {
        marker.rotation = -heading - mapView.mapOrientation
    }

    override var rotation: Float
        get() = heading
        set(value) {
            heading = value
            marker.rotation = -value - mapView.mapOrientation
        }
    override var position: Position
        get() = Position(marker.position.latitude, marker.position.longitude)
        set(value) {marker.position = value.toGeoPoint()}

    override var title: String
        get() = marker.title ?: ""
        set(value) { marker.title = value }

    override var snippet: String
        get() = marker.snippet ?: ""
        set(value) { marker.snippet = value }

    override fun setIcon(icon: Int, color: Int) {
        marker.icon = buildIcon(icon, color)
    }

    override fun remove() {
        marker.remove(mapView)
    }
}