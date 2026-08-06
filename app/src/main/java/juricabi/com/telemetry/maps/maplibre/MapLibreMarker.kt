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
    /**
     * A layer to stay underneath, where being made later is not the same as
     * belonging on top. Null puts this above everything, which is the usual
     * case.
     */
    private val below: String?,
    private val whenReady: ((Style) -> Unit) -> Unit,
    /** Told when this marker goes, so nothing keeps a dead one. */
    private val onRemoved: ((MapLibreMarker) -> Unit)? = null
) : MapMarker {

    /** What to name if something else must be kept above this one. */
    val layerName: String get() = layerId

    /** What this marker calls itself on the features it puts on the map. */
    val markerId: String get() = id

    /**
     * Whether tapping it is worth anything.
     *
     * Only the aircraft from Flightradar have anything to say; the model has a
     * name for none of this. osmdroid decides the same way — it keeps the
     * bubble aside and puts it back only for a marker with words in it.
     */
    fun hasSomethingToSay(): Boolean = title.isNotEmpty() || snippet.isNotEmpty()

    private val sourceId = "mark-src-$id"
    private val layerId = "mark-lyr-$id"
    private val imageId = "mark-img-$id"

    private var source: GeoJsonSource? = null
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
            // Above whatever exists so far: markers are made after the lines
            // and belong over them, as they do on the osmdroid map. Unless
            // something has been named to stay under — and then only if it is
            // still there, since a marker can be taken off the map at any time.
            if (below != null && s.getLayer(below) != null) {
                s.addLayerBelow(lyr, below)
            } else {
                s.addLayer(lyr)
            }
            source = src
        }
    }

    private fun feature(): Feature {
        val at = Feature.fromGeometry(Point.fromLngLat(where.lon, where.lat))
        at.addNumberProperty("bearing", heading)
        // Its own name, carried on the feature: a query of what is under a
        // finger hands back features and does not say which layer each came
        // from, so without this there is no way back from a hit to the marker
        // that was hit.
        at.addStringProperty("marker", id)
        return at
    }

    /**
     * Place and heading together, in one update, because they belong together.
     *
     * Through the guard rather than straight at the source held here: after a
     * change of map that source belongs to a style that has been replaced, and
     * writing to one of those throws rather than being ignored.
     */
    private fun push() = whenReady { source?.setGeoJson(feature()) }

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
            R.drawable.ic_heli -> R.drawable.ic_heli_outline
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

    override fun place(position: Position, rotation: Float) {
        if (position == where && rotation == heading) return
        where = position
        heading = rotation
        push()
    }

    override fun setIcon(icon: Int, color: Int) {
        iconRes = icon
        iconColor = color
        // Replacing the image under the name the layer already points at, so
        // the layer does not have to be rebuilt to change colour. Through
        // whenReady rather than the style held here, so a style that has since
        // been replaced is left alone rather than written to.
        whenReady { it.addImage(imageId, bitmapFor(icon, color)) }
    }

    override fun remove() {
        removed = true
        onRemoved?.invoke(this)
        whenReady { s ->
            s.removeLayer(layerId)
            s.removeSource(sourceId)
            s.removeImage(imageId)
        }
        source = null
    }
}
