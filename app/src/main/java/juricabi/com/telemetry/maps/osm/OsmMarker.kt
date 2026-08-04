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
import org.osmdroid.views.overlay.Overlay

class OsmMarker(
    icon: Int,
    color: Int?,
    position: Position,
    private val mapView: MapView,
    private val context: Context,
    /**
     * An overlay to go under, where being added later is not the same as
     * belonging on top. Null appends, which is the usual case.
     */
    below: Overlay? = null,
    /** Told when this marker goes, so nothing keeps a dead one. */
    private val onRemoved: ((OsmMarker) -> Unit)? = null
) : MapMarker {

    private val marker = Marker(mapView)
    private var heading: Float = 0f

    /** For anything that has to be kept above this one. */
    internal val overlay: Overlay get() = marker

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
        // Last in the list is drawn on top, so a marker that belongs underneath
        // another has to be put in at its place rather than added to the end.
        val at = if (below != null) mapView.overlayManager.indexOf(below) else -1
        if (at >= 0) {
            mapView.overlayManager.add(at, marker)
        } else {
            mapView.overlayManager.add(marker)
        }
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
        // and out of the list the map keeps for turning them all, which was
        // only ever added to: the Flightradar traffic is thrown away and made
        // again on every poll, so that list grew for as long as the app was
        // open and every rotation of the map turned markers that had not been
        // on it for hours.
        onRemoved?.invoke(this)
    }
}