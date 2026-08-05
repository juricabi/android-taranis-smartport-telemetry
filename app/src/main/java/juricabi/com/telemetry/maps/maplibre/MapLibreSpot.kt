package juricabi.com.telemetry.maps.maplibre

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import juricabi.com.telemetry.maps.Position
import org.maplibre.android.location.ModelIndicator
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
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

    private val arrowLyrId = "spot-arrow-lyr-$id"
    private val ringSrcId = "spot-ring-src-$id"
    private val ringLyrId = "spot-ring-lyr-$id"
    private val ringEdgeLyrId = "spot-ring-edge-lyr-$id"
    private var imageVersion = 0
    private fun imageId() = "spot-img-$id-$imageVersion"
    private val arrow = ModelIndicator(arrowLyrId)

    val arrowLayer: String get() = arrowLyrId

    private var ringSrc: GeoJsonSource? = null

    private var where: Position? = null
    private var accuracy = 0f

    /** Not a number until a compass has said, and then the arrow appears. */
    private var bearing = Float.NaN
    private var colour = 0xFF2196F3.toInt()
    private var shown = true
    private var arrowDisplayed = false

    init {
        whenReady { s ->
            s.addImage(imageId(), arrowBitmap(colour))

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

            // Like the aircraft, the arrow is a renderer property rather than
            // a GeoJSON point. A compass update used to rebuild and tile this
            // source on every display tick; the two arrows alone accounted for
            // almost nine thousand tile parses in the 43-second stress replay.
            arrow.set(
                ModelIndicator.image(imageId()),
                ModelIndicator.imageScale(1f),
                ModelIndicator.visible(false)
            )
            s.addLayer(arrow.asLayer)
            push()
        }
    }

    fun setColour(value: Int) {
        colour = value
        // Through whenReady rather than the style held here: after a change of
        // map type that one has been replaced, and writing to a replaced style
        // throws rather than being ignored.
        whenReady { s ->
            val old = imageId()
            imageVersion++
            s.addImage(imageId(), arrowBitmap(value))
            arrow.set(ModelIndicator.image(imageId()))
            s.removeImage(old)
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
        if (!moved && !accuracyChanged && !bearingChanged) return
        where = position
        accuracy = accuracyMetres
        bearing = headingDegrees
        // Turning the phone does not move its 128-point accuracy ring. The old
        // all-or-nothing push rebuilt that polygon at compass rate; replay also
        // rewrote both sources every display tick even through CSV rows whose
        // recorded place had not changed. Keep each renderer source tied only
        // to the facts that can change what it draws.
        if (moved || bearingChanged) pushArrow(moved, bearingChanged)
        if (moved || accuracyChanged) pushRing()
    }

    fun position(): Position? = where

    fun accuracy(): Float = accuracy

    fun remove() {
        whenReady { s ->
            s.removeLayer(arrowLyrId)
            s.removeLayer(ringEdgeLyrId)
            s.removeLayer(ringLyrId)
            s.removeSource(ringSrcId)
            s.removeImage(imageId())
        }
        arrowDisplayed = false
        ringSrc = null
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
        val at = where
        val shouldDisplay = at != null && shown && !bearing.isNaN()
        if (!shouldDisplay) {
            // Visibility is a layout property. Cross that boundary only once;
            // reapplying it at compass rate makes the layer shimmer.
            if (arrowDisplayed) {
                arrow.set(ModelIndicator.visible(false))
                arrowDisplayed = false
            }
            return@whenReady
        }

        val revealing = !arrowDisplayed
        // A compass event changes only the bearing. Do not resend the same
        // location and visibility with every sensor sample.
        when {
            revealing -> arrow.set(
                ModelIndicator.place(at!!.lat, at.lon),
                ModelIndicator.turn(bearing.toDouble()),
                ModelIndicator.visible(true)
            )
            positionChanged && bearingChanged -> arrow.set(
                ModelIndicator.place(at!!.lat, at.lon),
                ModelIndicator.turn(bearing.toDouble())
            )
            positionChanged -> arrow.set(ModelIndicator.place(at!!.lat, at.lon))
            bearingChanged -> arrow.set(ModelIndicator.turn(bearing.toDouble()))
        }
        if (revealing) arrowDisplayed = true
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
}
