package juricabi.com.telemetry.maps.maplibre

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.core.graphics.drawable.DrawableCompat
import juricabi.com.telemetry.R
import juricabi.com.telemetry.maps.MapMarker
import juricabi.com.telemetry.maps.Position
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/**
 * A marker, as a symbol layer rather than an annotation.
 *
 * MapLibre keeps the old annotation Marker, and it would have been a line of
 * code — but it cannot be turned, and the one marker that matters here is an
 * aircraft that has to point where it is going.
 *
 * A symbol layer turns, and turns *with the map*: `iconRotate` is read against
 * the map's own north once the alignment is set, so the heading goes in as it
 * is. osmdroid had to be handed `-heading - mapOrientation` and re-handed it on
 * every rotation of the map, which is a whole class of bug that does not exist
 * here.
 */
class MapLibreMarker(
    private val context: Context,
    icon: Int,
    color: Int?,
    position: Position,
    private val id: String,
    private val above: String,
    private val whenReady: ((Style) -> Unit) -> Unit
) : MapMarker {

    private val sourceId = "mark-src-$id"
    private val layerId = "mark-lyr-$id"
    private val imageId = "mark-img-$id"

    private var source: GeoJsonSource? = null
    private var layer: SymbolLayer? = null
    private var style: Style? = null
    private var removed = false

    private var where = position
    private var heading = 0f
    private var iconRes = icon
    private var iconColor = color

    override var title: String = ""
    override var snippet: String = ""

    init {
        whenReady { s ->
            if (removed) return@whenReady
            style = s
            s.addImage(imageId, bitmapFor(iconRes, iconColor))
            val src = GeoJsonSource(sourceId, feature())
            val lyr = SymbolLayer(layerId, sourceId).withProperties(
                PropertyFactory.iconImage(imageId),
                // Read off the feature rather than set on the layer. The model
                // is moved and turned on every frame the screen draws, and as
                // two calls those are two separate updates: one of them lands
                // first, so the model is drawn for a frame at its new place
                // still pointing the old way. Carried on the feature they
                // arrive together, and it is one call rather than two.
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                // A marker is a position, not a label competing for room: it is
                // drawn where it is even where two of them touch, which over a
                // field of Flightradar traffic they will.
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
            s.addSource(src)
            s.addLayerAbove(lyr, above)
            source = src
            layer = lyr
        }
    }

    private fun feature(): Feature {
        val at = Feature.fromGeometry(Point.fromLngLat(where.lon, where.lat))
        at.addNumberProperty("bearing", heading)
        return at
    }

    /** Place and heading together, in one update, because they belong together. */
    private fun push() {
        source?.setGeoJson(feature())
    }

    /** The same icon the map has always drawn, rendered once into a bitmap. */
    private fun bitmapFor(icon: Int, color: Int?): Bitmap {
        val drawable = buildIcon(icon, color)
        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 48
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 48
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bitmap
    }

    private fun buildIcon(icon: Int, color: Int?): Drawable {
        val body = context.resources.getDrawable(icon).mutate()
        if (color != null) DrawableCompat.setTint(body, color)
        val outlineIcon = when (icon) {
            R.drawable.ic_plane -> R.drawable.ic_plane_outline
            R.drawable.ic_fixedwing -> R.drawable.ic_fixedwing_outline
            else -> return body
        }
        // dark silhouette underneath, so a light marker stays readable on
        // satellite imagery — the same as the map has always done
        val outline = context.resources.getDrawable(outlineIcon).mutate()
        return LayerDrawable(arrayOf(outline, body))
    }

    override var rotation: Float
        get() = heading
        set(value) {
            heading = value
            push()
        }

    override var position: Position
        get() = where
        set(value) {
            where = value
            push()
        }

    override fun setIcon(icon: Int, color: Int) {
        iconRes = icon
        iconColor = color
        // Replacing the image under the name the layer already points at, so
        // the layer does not have to be rebuilt to change colour.
        style?.addImage(imageId, bitmapFor(icon, color))
    }

    override fun remove() {
        removed = true
        whenReady { s ->
            s.removeLayer(layerId)
            s.removeSource(sourceId)
            s.removeImage(imageId)
        }
        layer = null
        source = null
        style = null
    }
}
