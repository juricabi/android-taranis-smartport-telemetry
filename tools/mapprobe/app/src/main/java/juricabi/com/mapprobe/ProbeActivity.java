package juricabi.com.mapprobe;

import android.app.Activity;
import android.os.Bundle;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.RasterLayer;
import org.maplibre.android.style.sources.RasterSource;
import org.maplibre.android.style.sources.TileSet;

/**
 * The decisive instrument for the raster ghost: the same MapLibre the
 * telemetry app pins, the same ESRI raster tiles, and nothing else at all —
 * no telemetry, no second GL surface, no custom layer, not even a theme.
 * When the app's 2D map goes grey on fresh ground, open this on the same
 * ground. If it starves too, phone-plus-MapLibre is proven and the answer
 * lives upstream; if it never starves, something in the app's process is
 * back in scope.
 */
public class ProbeActivity extends Activity {

    private MapView mapView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapLibre.getInstance(getApplicationContext());
        mapView = new MapView(this);
        mapView.onCreate(savedInstanceState);
        setContentView(mapView);
        mapView.getMapAsync(map -> {
            TileSet tiles = new TileSet("2.1.0",
                "https://server.arcgisonline.com/ArcGIS/rest/services/"
                    + "World_Imagery/MapServer/tile/{z}/{y}/{x}");
            tiles.setMinZoom(0f);
            tiles.setMaxZoom(18f);
            map.setStyle(new Style.Builder()
                .withSource(new RasterSource("esri", tiles, 256))
                .withLayer(new RasterLayer("ground", "esri")));
            map.setCameraPosition(new CameraPosition.Builder()
                .target(new LatLng(44.55, 15.35)).zoom(12).build());
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mapView.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }
}
