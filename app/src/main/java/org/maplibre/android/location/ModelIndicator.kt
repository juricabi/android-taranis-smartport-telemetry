package org.maplibre.android.location

import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.TransitionOptions

/**
 * Access to MapLibre's package-private [LocationIndicatorLayer].
 *
 * Unlike a symbol backed by a GeoJSON source, its position and bearing are
 * renderer properties. Moving it therefore does not send a new source to a
 * worker on every frame.
 */
class ModelIndicator(id: String) {

    private val layer = LocationIndicatorLayer(id)

    init {
        // The screen owns the easing. A second transition here would make the
        // model belong to a different moment from the camera.
        layer.setLocationTransition(TransitionOptions(0, 0))
        layer.setBearingTransition(TransitionOptions(0, 0))
        layer.setProperties(LocationPropertyFactory.perspectiveCompensation(0f))
    }

    val asLayer: Layer get() = layer

    fun set(vararg values: PropertyValue<*>) = layer.setProperties(*values)

    companion object {
        fun image(name: String): PropertyValue<String> =
            LocationPropertyFactory.bearingImage(name)

        fun imageScale(factor: Float): PropertyValue<Float> =
            LocationPropertyFactory.bearingImageSize(factor)

        fun place(lat: Double, lon: Double): PropertyValue<Array<Double>> =
            LocationPropertyFactory.location(arrayOf(lat, lon, 0.0))

        fun turn(degrees: Double): PropertyValue<Double> =
            LocationPropertyFactory.bearing(degrees)
    }
}
