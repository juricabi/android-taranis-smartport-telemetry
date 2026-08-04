package juricabi.com.telemetry.maps.osm

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.core.graphics.drawable.DrawableCompat
import juricabi.com.telemetry.R
import juricabi.com.telemetry.maps.MapMarker
import juricabi.com.telemetry.maps.Position
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class OsmMarker(icon: Int, color: Int?, position: Position, private val mapView: MapView, private val context: Context) : MapMarker {

    private val marker = Marker(mapView)
    private var heading: Float = 0f

    private fun buildIcon(icon: Int, color: Int?): Drawable {
        val body = context.resources.getDrawable(icon).mutate()
        if (color != null) DrawableCompat.setTint(body, color)
        val outlineIcon = when (icon) {
            R.drawable.ic_plane -> R.drawable.ic_plane_outline
            R.drawable.ic_fixedwing -> R.drawable.ic_fixedwing_outline
            else -> return body
        }
        // dark silhouette underneath, so a light marker stays readable on satellite imagery
        val outline = context.resources.getDrawable(outlineIcon).mutate()
        return LayerDrawable(arrayOf(outline, body))
    }

    /**
     * The bubble osmdroid gives every marker, kept aside rather than thrown
     * away: it is put back only for a marker that has something to say.
     */
    private val bubble = marker.infoWindow

    init {
        marker.icon = buildIcon(icon, color)
        marker.position = position.toGeoPoint()
        // Tapping the model used to open an empty bubble, because every marker
        // gets one whether or not it has a title. Only the aircraft from
        // FlightRadar have anything to put in one.
        marker.infoWindow = null
        mapView.overlayManager.add(marker)
    }

    private fun showBubbleIfWorthIt() {
        val hasSomethingToSay = !marker.title.isNullOrEmpty() || !marker.snippet.isNullOrEmpty()
        marker.infoWindow = if (hasSomethingToSay) bubble else null
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
        set(value) {
            marker.title = value
            showBubbleIfWorthIt()
        }

    override var snippet: String
        get() = marker.snippet ?: ""
        set(value) {
            marker.snippet = value
            showBubbleIfWorthIt()
        }

    override fun setIcon(icon: Int, color: Int) {
        marker.icon = buildIcon(icon, color)
        mapView.invalidate()
    }

    override fun remove() {
        marker.remove(mapView)
    }
}