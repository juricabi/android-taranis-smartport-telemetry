package juricabi.com.telemetry.maps.maplibre

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import juricabi.com.telemetry.maps.Position
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Where a phone is, drawn: an arrow if its bearing is known, and the ring of
 * how well the place itself is known around it.
 *
 * There are two — where this phone is now, and where it stood while the flight
 * being replayed was recorded — and they are the same thing in two colours,
 * which is why this exists once rather than twice.
 *
 * osmdroid got both from MyLocationNewOverlay, which came with a location
 * provider of its own. Nothing here has one: the screen owns the only location
 * listener there is and hands the position over, which is the right way round
 * and was already how the interface was written.
 */
class MapLibreSpot(
    private val context: Context,
    private val id: String,
    private val whenReady: ((Style) -> Unit) -> Unit
) {

    private val arrowSrcId = "spot-arrow-src-$id"
    private val arrowLyrId = "spot-arrow-lyr-$id"
    private val ringSrcId = "spot-ring-src-$id"
    private val ringLyrId = "spot-ring-lyr-$id"
    private val imageId = "spot-img-$id"

    private var style: Style? = null
    private var arrowSrc: GeoJsonSource? = null
    private var ringSrc: GeoJsonSource? = null
    private var arrowLyr: SymbolLayer? = null

    private var where: Position? = null
    private var accuracy = 0f

    /** Not a number until a compass has said, and then the arrow appears. */
    private var bearing = Float.NaN
    private var colour = 0xFF2196F3.toInt()
    private var shown = true

    init {
        whenReady { s ->
            style = s
            s.addImage(imageId, arrowBitmap(colour))

            val ring = GeoJsonSource(ringSrcId)
            s.addSource(ring)
            // The ring first and the arrow on top of it, and both of them
            // before any line or marker is made — so the flight is drawn over
            // these, which is where osmdroid draws it too.
            s.addLayer(
                FillLayer(ringLyrId, ringSrcId).withProperties(
                    PropertyFactory.fillColor(colour),
                    PropertyFactory.fillOpacity(0.15f)
                )
            )
            ringSrc = ring

            val arrow = GeoJsonSource(arrowSrcId)
            s.addSource(arrow)
            val lyr = SymbolLayer(arrowLyrId, arrowSrcId).withProperties(
                PropertyFactory.iconImage(imageId),
                PropertyFactory.iconRotate(0f),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
            s.addLayer(lyr)
            arrowSrc = arrow
            arrowLyr = lyr
            push()
        }
    }

    fun setColour(value: Int) {
        colour = value
        style?.addImage(imageId, arrowBitmap(value))
        style?.getLayerAs<FillLayer>(ringLyrId)
            ?.setProperties(PropertyFactory.fillColor(value))
    }

    fun setVisible(value: Boolean) {
        shown = value
        push()
    }

    fun place(position: Position?, accuracyMetres: Float, headingDegrees: Float) {
        where = position
        accuracy = accuracyMetres
        bearing = headingDegrees
        push()
    }

    fun position(): Position? = where

    fun accuracy(): Float = accuracy

    fun remove() {
        whenReady { s ->
            s.removeLayer(arrowLyrId)
            s.removeLayer(ringLyrId)
            s.removeSource(arrowSrcId)
            s.removeSource(ringSrcId)
            s.removeImage(imageId)
        }
        style = null
        arrowSrc = null
        ringSrc = null
        arrowLyr = null
    }

    private fun push() {
        val arrow = arrowSrc ?: return
        val ring = ringSrc ?: return
        val at = where
        // An empty collection rather than a hidden layer: a layer switched off
        // and on again is a style change, and this is written to on every fix.
        if (at == null || !shown) {
            arrow.setGeoJson(nothing())
            ring.setGeoJson(nothing())
            return
        }
        // The arrow is what says which way the phone faces, so with no bearing
        // there is nothing honest to draw — the ring alone says where it is.
        // osmdroid was handed a one pixel bitmap to say the same thing.
        // Branch by hand rather than in the argument: a FeatureCollection and a
        // Feature have only GeoJson in common, and setGeoJson takes each of
        // them and not that.
        if (bearing.isNaN()) {
            arrow.setGeoJson(nothing())
        } else {
            arrow.setGeoJson(Feature.fromGeometry(Point.fromLngLat(at.lon, at.lat)))
            arrowLyr?.setProperties(PropertyFactory.iconRotate(bearing))
        }
        if (accuracy > 0f) {
            ring.setGeoJson(Feature.fromGeometry(ringAround(at, accuracy)))
        } else {
            ring.setGeoJson(nothing())
        }
    }

    /** Nothing to draw, said in the one way a source understands. */
    private fun nothing() =
        org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList<Feature>())

    /**
     * The accuracy ring, in metres on the ground rather than pixels.
     *
     * MapLibre's circle layer takes a radius in pixels, which is a ring that
     * means a different distance at every zoom — the one thing this circle
     * exists to say. Drawn as a shape in real coordinates it stays honest.
     */
    private fun ringAround(at: Position, metres: Float): Polygon {
        val steps = 48
        val latPerMetre = 1.0 / 111320.0
        val lonPerMetre = 1.0 / (111320.0 * Math.cos(Math.toRadians(at.lat)))
        val edge = ArrayList<Point>(steps + 1)
        for (i in 0..steps) {
            val angle = 2.0 * Math.PI * i / steps
            edge.add(
                Point.fromLngLat(
                    at.lon + metres * lonPerMetre * Math.cos(angle),
                    at.lat + metres * latPerMetre * Math.sin(angle)
                )
            )
        }
        return Polygon.fromLngLats(listOf(edge))
    }

    /** The same arrow osmdroid draws, so the two maps agree about what it is. */
    private fun arrowBitmap(color: Int): Bitmap {
        val px = (context.resources.displayMetrics.density * 26).toInt()
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val u = px / 24f
        val path = Path()
        path.moveTo(12f * u, 3f * u)
        path.lineTo(18.5f * u, 20f * u)
        path.lineTo(12f * u, 16.2f * u)
        path.lineTo(5.5f * u, 20f * u)
        path.close()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.style = Paint.Style.FILL
        fill.color = color
        canvas.drawPath(path, fill)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG)
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = u
        edge.color = 0xFF000000.toInt()
        canvas.drawPath(path, edge)
        return bitmap
    }
}
