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
import org.maplibre.android.location.ModelIndicator
import org.maplibre.android.maps.Style

/**
 * The model aircraft, drawn through MapLibre's renderer-owned location layer.
 *
 * A normal symbol moves by replacing its GeoJSON source. That replacement is
 * tiled on a worker, so frame-rate writes arrive in bursts. The location layer
 * reads these properties while it renders and therefore stays on the same
 * frame as the camera.
 */
class MapLibreModelMarker(
    private val context: Context,
    icon: Int,
    color: Int?,
    position: Position,
    private val whenReady: ((Style) -> Unit) -> Unit
) : MapMarker {

    private val layerId = "model-lyr"
    val layerName: String get() = layerId

    private val indicator = ModelIndicator(layerId)
    private var where = position
    private var heading = 0f
    private var iconRes = icon
    private var iconColor = color
    private var look = 0
    private var removed = false

    override var title: String = ""
    override var snippet: String = ""

    init {
        whenReady { style ->
            if (removed) return@whenReady
            style.addImage(imageId(), planeBitmap())
            indicator.set(
                ModelIndicator.image(imageId()),
                ModelIndicator.imageScale(1f),
                ModelIndicator.place(where.lat, where.lon),
                ModelIndicator.turn(heading.toDouble())
            )
            style.addLayer(indicator.asLayer)
        }
    }

    private fun imageId() = "model-img-$look"

    override var rotation: Float
        get() = heading
        set(value) {
            if (removed || value == heading) return
            heading = value
            whenReady { indicator.set(ModelIndicator.turn(value.toDouble())) }
        }

    override var position: Position
        get() = where
        set(value) {
            if (removed || value == where) return
            where = value
            whenReady { indicator.set(ModelIndicator.place(value.lat, value.lon)) }
        }

    /** Place and turn in one renderer write because they describe one frame. */
    override fun place(position: Position, rotation: Float) {
        if (removed || (position == where && rotation == heading)) return
        where = position
        heading = rotation
        whenReady {
            indicator.set(
                ModelIndicator.place(position.lat, position.lon),
                ModelIndicator.turn(rotation.toDouble())
            )
        }
    }

    override fun setIcon(icon: Int, color: Int) {
        if (removed || (icon == iconRes && color == iconColor)) return
        iconRes = icon
        iconColor = color
        whenReady { style ->
            // MapLibre caches an image by name. Point at the new one before
            // dropping the old texture or the old model stays on screen.
            val old = imageId()
            look++
            style.addImage(imageId(), planeBitmap())
            indicator.set(ModelIndicator.image(imageId()))
            style.removeImage(old)
        }
    }

    override fun remove() {
        if (removed) return
        removed = true
        whenReady { style ->
            style.removeLayer(layerId)
            style.removeImage(imageId())
        }
    }

    private fun planeBitmap(): Bitmap {
        val drawable = buildIcon(iconRes, iconColor)
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 48
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 48
        // LocationIndicatorLayer sizes from width and draws a square. Keeping
        // the bitmap square prevents a tall model from being crushed.
        val side = Math.max(width, height)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(
            (side - width) / 2, (side - height) / 2,
            (side + width) / 2, (side + height) / 2
        )
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
        val outline = context.resources.getDrawable(outlineIcon).mutate()
        return LayerDrawable(arrayOf(outline, body))
    }
}
