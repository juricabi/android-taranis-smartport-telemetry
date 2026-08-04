package juricabi.com.telemetry.maps.maplibre

import org.maplibre.android.maps.Style
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

    /** Matching OsmMapWrapper's constants, which the settings already store. */
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

    fun forType(mapType: Int): Style.Builder {
        val base = when (mapType) {
            MAP_TYPE_TOPO -> TOPO
            MAP_TYPE_SATELLITE, MAP_TYPE_SATELLITE_HYBRID -> ESRI
            else -> OSM
        }
        // The maximum zoom each of these actually has imagery for. Past it
        // MapLibre keeps drawing the deepest tiles it has, scaled up, which is
        // the blurry-rather-than-blank behaviour the map already had.
        val maxZoom = when (mapType) {
            MAP_TYPE_TOPO -> 17f
            MAP_TYPE_SATELLITE, MAP_TYPE_SATELLITE_HYBRID -> 19f
            else -> 19f
        }

        val builder = Style.Builder()
            .withSource(raster("base-src", base, maxZoom))
            .withLayer(RasterLayer(BASE_LAYER, "base-src"))

        if (mapType == MAP_TYPE_SATELLITE_HYBRID) {
            // Roads and place names over the imagery, as two more transparent
            // raster layers — the same two overlays osmdroid was stacking.
            builder
                .withSource(raster("roads-src", ESRI_ROADS, maxZoom))
                .withLayer(RasterLayer("roads", "roads-src"))
                .withSource(raster("places-src", ESRI_PLACES, maxZoom))
                .withLayer(RasterLayer("places", "places-src"))
        }
        return builder
    }

    /**
     * The layer everything of ours sits above.
     *
     * Whatever the topmost tile layer of this style turned out to be — the
     * flight has to go above the place names, not under them.
     */
    fun topTileLayer(mapType: Int): String =
        if (mapType == MAP_TYPE_SATELLITE_HYBRID) "places" else BASE_LAYER

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
