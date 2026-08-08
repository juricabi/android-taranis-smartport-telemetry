package juricabi.com.telemetry.maps.maplibre

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import juricabi.com.telemetry.maps.Position
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
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
 * provider of its own. Nothing here has one: the telemetry service owns the
 * single location and compass readers and hands their state to either view.
 */
class MapLibreSpot(
    private val context: Context,
    private val id: String,
    private val whenReady: ((Style) -> Unit) -> Unit
) {

    private val arrowLyrId = "spot-arrow-lyr-$id"
    private val arrowSrcId = "spot-arrow-src-$id"
    private val ringSrcId = "spot-ring-src-$id"
    private val ringLyrId = "spot-ring-lyr-$id"
    private val ringEdgeLyrId = "spot-ring-edge-lyr-$id"
    private var imageVersion = 0
    private fun arrowImageId() = "spot-arrow-img-$id-$imageVersion"
    private fun dotImageId() = "spot-dot-img-$id-$imageVersion"

    val arrowLayer: String get() = arrowLyrId

    private var ringSrc: GeoJsonSource? = null
    private var arrowSrc: GeoJsonSource? = null
    private var arrowLyr: SymbolLayer? = null

    private var where: Position? = null
    private var accuracy = 0f

    /** Not a number until a compass has said; position still appears as a dot. */
    private var bearing = Float.NaN
    private var colour = 0xFF2196F3.toInt()
    private var shown = true
    private var spotDisplayed = false

    init {
        whenReady { s ->
            s.addImage(arrowImageId(), arrowBitmap(colour))
            s.addImage(dotImageId(), dotBitmap(colour))

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
            // and its edge drawn, the way the ground view draws it. Filled and
            // nothing more, the ring has no edge at all where the fill is faint
            // over pale ground, and how far it reaches — the whole of what it
            // says — cannot be read.
            //
            // A line layer over a polygon draws that polygon's outline, so both
            // come off the one source and cannot disagree about where the edge
            // is.
            s.addLayer(
                LineLayer(ringEdgeLyrId, ringSrcId).withProperties(
                    PropertyFactory.lineColor(colour),
                    PropertyFactory.lineWidth(0.8f)
                )
            )
            ringSrc = ring

            // An ordinary symbol, deliberately. This arrow spent a while as
            // MapLibre's own LocationIndicatorLayer — renderer properties,
            // no source to retile — and two of those standing in the style
            // stopped every raster tile on this phone from being DRAWN:
            // fetches completed, the ground stayed grey anywhere the camera
            // was not writing, and a week of blaming the network followed.
            // A symbol has no such opinions. The old cost that pushed the
            // arrow off GeoJSON is still respected below: only a moved FIX
            // touches the source; a compass tick turns the layer property
            // and retiles nothing.
            val arrowSource = GeoJsonSource(arrowSrcId)
            s.addSource(arrowSource)
            val arrow = SymbolLayer(arrowLyrId, arrowSrcId).withProperties(
                PropertyFactory.iconImage(dotImageId()),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment(
                    Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.visibility(Property.NONE)
            )
            s.addLayer(arrow)
            arrowSrc = arrowSource
            arrowLyr = arrow
            push()
        }
    }

    fun setColour(value: Int) {
        colour = value
        // Through whenReady rather than the style held here: after a change of
        // map type that one has been replaced, and writing to a replaced style
        // throws rather than being ignored.
        whenReady { s ->
            val oldArrow = arrowImageId()
            val oldDot = dotImageId()
            imageVersion++
            s.addImage(arrowImageId(), arrowBitmap(value))
            s.addImage(dotImageId(), dotBitmap(value))
            arrowLyr?.setProperties(
                PropertyFactory.iconImage(
                    if (bearing.isNaN()) dotImageId() else arrowImageId()
                )
            )
            s.removeImage(oldArrow)
            s.removeImage(oldDot)
            s.getLayerAs<FillLayer>(ringLyrId)?.setProperties(PropertyFactory.fillColor(value))
            s.getLayerAs<LineLayer>(ringEdgeLyrId)?.setProperties(PropertyFactory.lineColor(value))
        }
    }

    fun setVisible(value: Boolean) {
        if (shown == value) return
        shown = value
        push()
    }

    fun place(position: Position?, accuracyMetres: Float, headingDegrees: Float) {
        val moved = where != position
        val accuracyChanged = !same(accuracy, accuracyMetres)
        val bearingChanged = !same(bearing, headingDegrees)
        // An arrow not yet revealed keeps asking: the reveal rides whenReady,
        // and a style busy loading a planet of tiles drops the work rather
        // than queue it — a phone lying still then never asked again, and
        // the arrow stayed missing for the whole session.
        val unrevealed = !spotDisplayed && position != null && shown
        if (!moved && !accuracyChanged && !bearingChanged && !unrevealed) return
        where = position
        accuracy = accuracyMetres
        bearing = headingDegrees
        // Turning the phone does not move its 128-point accuracy ring. The old
        // all-or-nothing push rebuilt that polygon at compass rate; replay also
        // rewrote both sources every display tick even through CSV rows whose
        // recorded place had not changed. Keep each renderer source tied only
        // to the facts that can change what it draws.
        if (moved || bearingChanged || unrevealed) pushArrow(moved, bearingChanged)
        if (moved || accuracyChanged || unrevealed) pushRing()
    }

    fun position(): Position? = where

    fun accuracy(): Float = accuracy

    fun remove() {
        whenReady { s ->
            s.removeLayer(arrowLyrId)
            s.removeLayer(ringEdgeLyrId)
            s.removeLayer(ringLyrId)
            s.removeSource(arrowSrcId)
            s.removeSource(ringSrcId)
            s.removeImage(arrowImageId())
            s.removeImage(dotImageId())
        }
        spotDisplayed = false
        ringSrc = null
        arrowSrc = null
        arrowLyr = null
    }

    /** Keep the arrow above native moving lines while its ring stays below. */
    fun raiseArrow(style: Style) {
        val layer = style.getLayer(arrowLyrId) ?: return
        style.removeLayer(layer)
        style.addLayer(layer)
    }

    private fun push() {
        pushArrow()
        pushRing()
    }

    private fun pushArrow(
        positionChanged: Boolean = true,
        bearingChanged: Boolean = true
    ) = whenReady {
        val lyr = arrowLyr ?: return@whenReady
        val src = arrowSrc ?: return@whenReady
        val at = where
        val shouldDisplay = at != null && shown
        if (!shouldDisplay) {
            // Visibility is a layout property. Cross that boundary only once;
            // reapplying it at compass rate makes the layer shimmer.
            if (spotDisplayed) {
                lyr.setProperties(PropertyFactory.visibility(Property.NONE))
                spotDisplayed = false
            }
            return@whenReady
        }

        val revealing = !spotDisplayed
        val pointing = !bearing.isNaN()
        // Only a moved fix touches the source; a compass event turns the
        // layer property and never resends the place with the sample.
        if (revealing || positionChanged) {
            src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(at!!.lon, at.lat)))
        }
        if (revealing || bearingChanged) {
            if (pointing) {
                lyr.setProperties(
                    PropertyFactory.iconImage(arrowImageId()),
                    PropertyFactory.iconRotate(bearing)
                )
            } else {
                lyr.setProperties(
                    PropertyFactory.iconImage(dotImageId()),
                    PropertyFactory.iconRotate(0f)
                )
            }
        }
        if (revealing) {
            lyr.setProperties(PropertyFactory.visibility(Property.VISIBLE))
            spotDisplayed = true
        }
    }

    private fun pushRing() = whenReady {
        val ring = ringSrc ?: return@whenReady
        val at = where
        if (at == null || !shown) {
            ring.setGeoJson(nothing())
            return@whenReady
        }
        if (accuracy > 0f) {
            ring.setGeoJson(Feature.fromGeometry(ringAround(at, accuracy)))
        } else {
            ring.setGeoJson(nothing())
        }
    }

    private fun same(left: Float, right: Float): Boolean =
        left == right || (left.isNaN() && right.isNaN())

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
        // Enough corners that it is a circle at any zoom. Forty-eight showed
        // its edges once the ring filled the screen — a good fix on a phone
        // standing still is a few metres across, and zoomed to that it was
        // plainly a polygon. It is a hundred and twenty-eight points built when
        // a fix arrives, which is nothing; the ground view draws its own from
        // sixty-four.
        val steps = 128
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

    /** Position without direction: visible and honest, rather than a north arrow. */
    private fun dotBitmap(color: Int): Bitmap {
        val px = (context.resources.displayMetrics.density * 26).toInt()
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val u = px / 24f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.style = Paint.Style.FILL
        fill.color = color
        canvas.drawCircle(12f * u, 12f * u, 6.25f * u, fill)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG)
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = u
        edge.color = 0xFF000000.toInt()
        canvas.drawCircle(12f * u, 12f * u, 6.25f * u, edge)
        return bitmap
    }
}
