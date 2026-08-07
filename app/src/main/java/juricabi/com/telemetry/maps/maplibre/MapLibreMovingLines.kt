package juricabi.com.telemetry.maps.maplibre

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.Keep
import juricabi.com.telemetry.maps.MapLine
import juricabi.com.telemetry.maps.MapMarker
import juricabi.com.telemetry.maps.Position
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CustomLayer

/**
 * JNI half of the moving-line custom layer.
 *
 * [create] returns two different lifetimes: the first pointer is transferred
 * to MapLibre's [CustomLayer], while the second is our safe handle to shared
 * line state. The map may destroy its renderer on another thread without
 * leaving a dangling pointer in a frame update.
 */
@Keep
internal object MapLibreMovingLinesNative {
    init {
        System.loadLibrary("telemetry-map-lines")
    }

    external fun create(): LongArray
    external fun setLine(
        stateHandle: Long,
        slot: Int,
        coordinates: DoubleArray,
        width: Float,
        color: Int
    )
    external fun clearLine(stateHandle: Long, slot: Int)
    external fun setModel(
        stateHandle: Long,
        latitude: Double,
        longitude: Double,
        heading: Float,
        visible: Boolean
    )
    external fun setModelImage(
        stateHandle: Long,
        pixels: IntArray,
        width: Int,
        height: Int,
        size: Float
    )
    external fun clearModel(stateHandle: Long)
    external fun setCamera(
        stateHandle: Long,
        latitude: Double,
        longitude: Double,
        bearing: Double
    )
    external fun commit(stateHandle: Long)
    external fun destroyHandle(stateHandle: Long)
    external fun abandonHost(host: Long)
}

/**
 * One non-tiled render layer for the three shapes that change every frame.
 *
 * GeoJSON sources and native annotations both rebuild vector tiles on a
 * worker. The trace measured 4--31 ms between a home/heading update and that
 * geometry being parsed, so no fixed delay can keep either endpoint attached
 * to a model that is already in the next frame. A custom layer is called from
 * MapLibre's render pass and receives that frame's exact projection matrix.
 */
internal class MapLibreMovingLineLayer(
    private val map: () -> MapLibreMap?,
    private val whenReady: ((Style) -> Unit) -> Unit,
    private val onNextDisplayFrame: (() -> Unit) -> Unit
) {
    companion object {
        const val LAYER_ID = "moving-lines-lyr"
        const val FLIGHT_HEAD = 0
        const val HOME = 1
        const val HEADING = 2
        const val CURRENT = 3
        private const val SLOT_COUNT = 4
    }

    private val owners = arrayOfNulls<MapLibreMovingLine>(SLOT_COUNT)
    private var modelOwner: MapLibreMovingModelMarker? = null
    private var layer: CustomLayer? = null
    private var stateHandle = 0L
    private var dirty = false
    private var commitScheduled = false
    private var closed = false

    init {
        whenReady { attach(it) }
    }

    fun makeLine(slot: Int): MapLine {
        require(slot in 0 until SLOT_COUNT)
        val line = MapLibreMovingLine(this, slot)
        owners[slot] = line
        publish(line)
        return line
    }

    fun makeModelMarker(
        context: Context,
        icon: Int,
        color: Int,
        position: Position
    ): MapLibreMovingModelMarker {
        modelOwner?.remove()
        return MapLibreMovingModelMarker(context, icon, color, position, this).also {
            modelOwner = it
            publishModelImage(it)
            publish(it)
        }
    }

    private fun attach(style: Style) {
        if (closed || layer != null) return
        val created = MapLibreMovingLinesNative.create()
        check(created.size == 2) { "Moving-line renderer returned no context" }
        val host = created[0]
        val handle = created[1]
        var transferred = false
        try {
            val custom = CustomLayer(LAYER_ID, host)
            transferred = true
            layer = custom
            stateHandle = handle
            style.addLayer(custom)
            for (owner in owners) owner?.let(::publish)
            modelOwner?.let {
                publishModelImage(it)
                publish(it)
            }
            commitFrame()
        } catch (failure: Throwable) {
            stateHandle = 0L
            MapLibreMovingLinesNative.destroyHandle(handle)
            if (!transferred) MapLibreMovingLinesNative.abandonHost(host)
            throw failure
        }
    }

    fun placeBelow(style: Style, boundary: String) {
        val custom = layer ?: return
        if (style.getLayer(LAYER_ID) == null || style.getLayer(boundary) == null) return
        style.removeLayer(custom)
        style.addLayerBelow(custom, boundary)
    }

    fun publish(line: MapLibreMovingLine) {
        if (closed || owners[line.slot] !== line) return
        val handle = stateHandle
        if (handle == 0L) return
        val points = line.points
        if (points.size < 2) {
            MapLibreMovingLinesNative.clearLine(handle, line.slot)
        } else {
            val coordinates = DoubleArray(points.size * 2)
            var at = 0
            for (point in points) {
                coordinates[at++] = point.lat
                coordinates[at++] = point.lon
            }
            MapLibreMovingLinesNative.setLine(
                handle, line.slot, coordinates, line.width, line.color
            )
        }
        changed()
    }

    fun publish(model: MapLibreMovingModelMarker) {
        if (closed || modelOwner !== model) return
        val handle = stateHandle
        if (handle == 0L) return
        MapLibreMovingLinesNative.setModel(
            handle,
            model.position.lat,
            model.position.lon,
            model.rotation,
            true
        )
        changed()
    }

    fun publishModelImage(model: MapLibreMovingModelMarker) {
        if (closed || modelOwner !== model) return
        val handle = stateHandle
        if (handle == 0L) return
        val bitmap = model.bitmap()
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val pixelRatio = if (bitmap.density > 0) {
                bitmap.density / 160f
            } else {
                model.displayDensity
            }
            MapLibreMovingLinesNative.setModelImage(
                handle,
                pixels,
                bitmap.width,
                bitmap.height,
                bitmap.width / pixelRatio.coerceAtLeast(1f)
            )
        } finally {
            bitmap.recycle()
        }
        changed()
    }

    fun remove(line: MapLibreMovingLine) {
        if (owners[line.slot] !== line) return
        owners[line.slot] = null
        val handle = stateHandle
        if (!closed && handle != 0L) {
            MapLibreMovingLinesNative.clearLine(handle, line.slot)
            changed()
        }
    }

    fun remove(model: MapLibreMovingModelMarker) {
        if (modelOwner !== model) return
        modelOwner = null
        val handle = stateHandle
        if (!closed && handle != 0L) {
            MapLibreMovingLinesNative.clearModel(handle)
            changed()
        }
    }

    /** Camera state this pending scene is meant to be drawn with. */
    fun stageCamera(position: Position, bearing: Double) {
        if (closed) return
        val handle = stateHandle
        if (handle == 0L) return
        MapLibreMovingLinesNative.setCamera(
            handle, position.lat, position.lon, bearing
        )
        changed()
    }

    /** Atomically expose every staged attachment to the render thread. */
    fun commitFrame() {
        commitScheduled = false
        if (closed || !dirty) return
        val handle = stateHandle
        if (handle == 0L) return
        MapLibreMovingLinesNative.commit(handle)
        dirty = false
        map()?.triggerRepaint()
    }

    private fun changed() {
        dirty = true
        if (commitScheduled) return
        commitScheduled = true
        // Non-flight changes (style load, colour, visibility) still need a
        // commit. markerStep normally commits earlier and this callback then
        // becomes a harmless no-op.
        onNextDisplayFrame {
            if (commitScheduled) commitFrame()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        val handle = stateHandle
        stateHandle = 0L
        if (handle != 0L) MapLibreMovingLinesNative.destroyHandle(handle)
        owners.fill(null)
        modelOwner = null
        dirty = false
        commitScheduled = false
    }
}

internal class MapLibreMovingLine(
    private val renderer: MapLibreMovingLineLayer,
    val slot: Int
) : MapLine() {
    val points = ArrayList<Position>()
    private var removed = false
    private var lineWidth = 4f
    private var lineColor = 0xFFFF0000.toInt()

    override var width: Float
        get() = lineWidth
        set(value) {
            if (lineWidth == value) return
            lineWidth = value
            renderer.publish(this)
        }

    override var color: Int
        get() = lineColor
        set(value) {
            if (lineColor == value) return
            lineColor = value
            renderer.publish(this)
        }

    override val size: Int
        get() = points.size

    override fun addPoints(points: List<Position>) {
        if (removed || points.isEmpty()) return
        this.points.addAll(points)
        renderer.publish(this)
    }

    override fun setPoint(index: Int, position: Position) {
        if (removed || index !in points.indices || points[index] == position) return
        points[index] = position
        renderer.publish(this)
    }

    override fun setPoints(points: List<Position>) {
        if (removed || this.points == points) return
        this.points.clear()
        this.points.addAll(points)
        renderer.publish(this)
    }

    override fun clear() {
        spoints.clear()
        points.clear()
        renderer.publish(this)
    }

    override fun remove() {
        if (removed) return
        removed = true
        spoints.clear()
        points.clear()
        renderer.remove(this)
    }
}

/**
 * The aircraft in the same immutable custom-layer scene as its three lines.
 * This removes the last cross-layer presentation race at their shared point.
 */
internal class MapLibreMovingModelMarker(
    private val context: Context,
    icon: Int,
    color: Int,
    position: Position,
    private val renderer: MapLibreMovingLineLayer
) : MapMarker {
    val displayDensity: Float get() = context.resources.displayMetrics.density

    private var where = position
    private var heading = 0f
    private var iconRes = icon
    private var iconColor = color
    private var removed = false

    override var title: String = ""
    override var snippet: String = ""

    override var rotation: Float
        get() = heading
        set(value) {
            if (removed || value == heading) return
            heading = value
            renderer.publish(this)
        }

    override var position: Position
        get() = where
        set(value) {
            if (removed || value == where) return
            where = value
            renderer.publish(this)
        }

    override fun place(position: Position, rotation: Float) {
        if (removed || (position == where && rotation == heading)) return
        where = position
        heading = rotation
        renderer.publish(this)
    }

    override fun setIcon(icon: Int, color: Int) {
        if (removed || (icon == iconRes && color == iconColor)) return
        iconRes = icon
        iconColor = color
        renderer.publishModelImage(this)
    }

    override fun remove() {
        if (removed) return
        removed = true
        renderer.remove(this)
    }

    fun bitmap(): Bitmap = mapLibreModelBitmap(context, iconRes, iconColor)
}
