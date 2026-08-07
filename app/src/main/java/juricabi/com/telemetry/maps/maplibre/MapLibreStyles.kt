package juricabi.com.telemetry.maps.maplibre

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * The same four maps, drawn by MapLibre instead of osmdroid.
 *
 * MapLibre is a vector renderer and normally reads a style from a server, which
 * is where the API keys and the accounts come in. It does not have to: a style
 * is a document, and one naming raster tiles is as valid as one naming vector
 * ones. These are built here, in code, from the same URLs osmdroid was handed —
 * so the maps do not change, nothing new is fetched, and there is still no key
 * to sign up for.
 */
object MapLibreStyles {

    /**
     * Which of the four maps. These numbers are stored in the settings and in
     * the saved state, so they are what they have always been rather than
     * starting at zero — an install that has chosen satellite has a 7 written
     * down and expects it to still mean satellite.
     */
    const val MAP_TYPE_DEFAULT = 5
    const val MAP_TYPE_TOPO = 6
    const val MAP_TYPE_SATELLITE = 7
    const val MAP_TYPE_SATELLITE_HYBRID = 8

    private const val OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    private const val TOPO = "https://tile.opentopomap.org/{z}/{x}/{y}.png"

    // ArcGIS serves /tile/{level}/{row}/{col}, which is z, then y, then x —
    // not the {z}/{x}/{y} every other tile server uses. Swapped by mistake the
    // imagery still loads, and shows somewhere else entirely.
    private const val ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services/" +
        "World_Imagery/MapServer/tile/{z}/{y}/{x}"
    private const val ESRI_ROADS = "https://server.arcgisonline.com/ArcGIS/rest/services/" +
        "Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}"
    private const val ESRI_PLACES = "https://server.arcgisonline.com/ArcGIS/rest/services/" +
        "Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"

    /** Where the flight, the markers and everything else are drawn above. */
    const val BASE_LAYER = "base"

    /**
     * The deepest zoom each of these actually has pictures for, in tile terms.
     *
     * Declared on the source so MapLibre stops asking past it and draws what it
     * has, scaled up — blurry rather than blank, which is what the map has
     * always done. Without it every tile past the end is a request that 404s
     * and a white square where the ground should be.
     */
    fun maxTileZoom(mapType: Int): Float = when (mapType) {
        MAP_TYPE_TOPO -> 17f
        // Seventeen: the deepest level ArcGIS has pictures for everywhere.
        // Its coverage past that is patchy, and it does not refuse a tile it
        // lacks — it serves a "map data not yet available" watermark, which
        // is white ground with writing on it. Seventeen everywhere, scaled
        // up past it: blurry, never blank.
        MAP_TYPE_SATELLITE, MAP_TYPE_SATELLITE_HYBRID -> 17f
        else -> 19f
    }

    fun forType(mapType: Int): Style.Builder {
        val base = when (mapType) {
            MAP_TYPE_TOPO -> TOPO
            MAP_TYPE_SATELLITE, MAP_TYPE_SATELLITE_HYBRID -> ESRI
            else -> OSM
        }
        val maxZoom = maxTileZoom(mapType)

        val builder = Style.Builder()
            // Under everything, and the same dark grey osmdroid was given.
            // Without one, anything not yet drawn — a tile still on its way, a
            // corner of the world with nothing over it — is whatever the
            // surface was cleared to, and that is white.
            .withLayer(
                BackgroundLayer("background").withProperties(
                    PropertyFactory.backgroundColor(Color.rgb(28, 28, 28))
                )
            )
            .withSource(raster("base-src", base, maxZoom))
            .withLayer(rasterLayer(BASE_LAYER, "base-src"))

        if (mapType == MAP_TYPE_SATELLITE_HYBRID) {
            // Roads and place names over the imagery, as two more transparent
            // raster layers — the same two overlays osmdroid was stacking.
            builder
                .withSource(raster("roads-src", ESRI_ROADS, maxZoom))
                .withLayer(rasterLayer("roads", "roads-src"))
                .withSource(raster("places-src", ESRI_PLACES, maxZoom))
                .withLayer(rasterLayer("places", "places-src"))
        }
        return builder
    }

    /** Tracking reuses the existing raster immediately; a fade looks like slip. */
    private fun rasterLayer(id: String, source: String): RasterLayer =
        RasterLayer(id, source).withProperties(PropertyFactory.rasterFadeDuration(0f))

    private fun raster(id: String, url: String, maxZoom: Float): RasterSource {
        val tiles = TileSet("2.1.0", url)
        tiles.minZoom = 0f
        tiles.maxZoom = maxZoom
        // 256, because these are all ordinary 256 pixel tiles. Left at
        // MapLibre's 512 default every one of them is drawn at twice its size,
        // which reads as a map stuck one zoom level too far in.
        return RasterSource(id, tiles, 256)
    }
}
