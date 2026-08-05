package org.maplibre.android.maps

/**
 * Access to MapLibre's package-private transform flag.
 *
 * Raster tiles use a pixel-snapped projection whenever this flag is false,
 * while vector and custom layers always use the unsnapped projection. Keeping
 * it true only while the chase camera is moving makes every layer use the same
 * matrix. MapLibre's own gesture detector drives this exact flag for a drag.
 */
object TrackingTransform {
    @JvmStatic
    fun setInProgress(map: MapLibreMap, inProgress: Boolean) {
        map.transform.setGestureInProgress(inProgress)
    }
}
