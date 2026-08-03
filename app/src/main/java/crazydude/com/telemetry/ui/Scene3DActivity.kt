package crazydude.com.telemetry.ui

import android.app.Activity
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import crazydude.com.telemetry.gl.TerrainRenderer
import crazydude.com.telemetry.gl.TerrainScene
import crazydude.com.telemetry.utils.Elevation
import crazydude.com.telemetry.utils.Imagery

/**
 * The flight in three dimensions: the ground it was flown over, the aerial
 * view draped on it, and the path above.
 *
 * The track is handed over in [pending] rather than through the Intent, since
 * a long flight is far past what an Intent will carry.
 */
class Scene3DActivity : Activity() {

    companion object {
        /** Set by whoever opens this screen, read once on the way in. */
        @Volatile
        var pending: List<TerrainScene.TrackPoint>? = null

        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_MY_LAT = "myLat"
        const val EXTRA_MY_LON = "myLon"
        const val EXTRA_MY_ACCURACY = "myAccuracy"

        /** Enough segments that the ring reads as a circle from any distance. */
        private const val CIRCLE_SEGMENTS = 64

        /** How often the view picks up what has arrived. */
        private const val FOLLOW_INTERVAL_MS = 500L
    }

    private lateinit var surface: GLSurfaceView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private val renderer = TerrainRenderer()
    private val scene = TerrainScene()

    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastTouchDown = 0L
    private var pinching = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var loader: Thread? = null

    /**
     * Whether the camera rides the model.
     *
     * There is no mode to pick: with nothing arriving the view frames what was
     * flown, and the moment a new point comes in it follows. Touching the
     * screen hands control over, a double tap gives it back.
     */
    private var following = true
    private var seenVersion = -1
    private var loadingTerrain = false
    private val handler = android.os.Handler()
    private val poll = object : Runnable {
        override fun run() {
            pickUpNewPoints()
            handler.postDelayed(this, FOLLOW_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Elevation.init(this)
        Imagery.init(this)

        surface = GLSurfaceView(this)
        surface.setEGLContextClientVersion(2)
        surface.setRenderer(renderer)
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.setShadowLayer(4f, 0f, 0f, Color.BLACK)
        status.setPadding(24, 24, 24, 24)

        hint = TextView(this)
        hint.setTextColor(Color.WHITE)
        hint.alpha = 0.75f
        hint.setShadowLayer(4f, 0f, 0f, Color.BLACK)
        hint.setPadding(24, 24, 24, 24)

        val root = FrameLayout(this)
        root.addView(surface, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val statusParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        statusParams.gravity = Gravity.TOP or Gravity.START
        root.addView(status, statusParams)
        val hintParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hintParams.gravity = Gravity.BOTTOM or Gravity.START
        root.addView(hint, hintParams)
        setContentView(root)

        val points = pending
        pending = null
        val fallbackLat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val fallbackLon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)

        val hasFlight = points != null && scene.setTrack(points)
        if (!hasFlight) {
            // Terrain is worth looking at before anything has flown over it, so
            // with no flight yet show the ground around wherever we are.
            if (fallbackLat.isNaN() || fallbackLon.isNaN()) {
                status.text = "No flight to show"
                return
            }
            scene.setOrigin(fallbackLat, fallbackLon, 0f)
        }
        // The frame is fixed from here: the ground is built in it, so
        // re-centring later would slide the terrain out from under the flight.
        scene.setOrigin(scene.originLat, scene.originLon, scene.originAltitude)
        val flight = points ?: emptyList()

        renderer.setTrack(scene.track, scene.shadow)
        renderer.groundUnderCamera = { x, z ->
            // back from metres to degrees to ask the terrain, which is cheap
            // enough at once per frame
            val lat = scene.originLat - z / 111320.0
            val lon = scene.originLon + x / (111320.0 * Math.cos(Math.toRadians(scene.originLat)))
            val h = scene.groundAt(lat, lon)
            if (h == null) null else h - scene.originAltitude
        }
        renderer.target = floatArrayOf(0f, heightOfTrack() / 2f, 0f)
        renderer.distance = Math.max(600f, scene.extent * 3f)
        status.text = "Loading terrain…"
        hint.text = "one finger turns · two fingers move and zoom · double tap follows"

        // the network and the tile decoding are far too slow for the UI thread
        val worker = Thread(Runnable {
            scene.loadTerrain(flight,
                { done, total ->
                    runOnUiThread {
                        status.text = "Loading terrain… " + done + " of " + total
                    }
                },
                {
                    // heights are in, so the path can sit on the ground before
                    // any imagery has arrived
                    runOnUiThread { renderer.setTrack(scene.track, scene.shadow) }
                })
            runOnUiThread {
                renderer.submit(scene.tiles)
                renderer.setTrack(scene.track, scene.shadow)
                showMyLocation()
                status.text = if (scene.tiles.isEmpty()) {
                    "No terrain here — showing the flight alone"
                } else {
                    ""
                }
                loadingTerrain = false
            }
        })
        worker.name = "terrain-load"
        loader = worker
        worker.start()
    }

    /**
     * Take whatever has arrived since last time. Rebuilding the path costs a
     * few thousand floats, which is nothing beside a frame, so there is no need
     * to be clever about appending.
     */
    private fun pickUpNewPoints() {
        val version = crazydude.com.telemetry.gl.LiveFlightPath.version
        if (version == seenVersion) return
        seenVersion = version
        val points = crazydude.com.telemetry.gl.LiveFlightPath.snapshot()
        if (points.size < 2) return

        scene.buildTrack(points)
        renderer.setTrack(scene.track, scene.shadow)

        val last = points[points.size - 1]
        val before = points[Math.max(0, points.size - 4)]
        renderer.setModel(
            scene.east(last.lon),
            last.altitudeMsl - scene.originAltitude,
            -scene.north(last.lat),
            courseBetween(before, last),
            Math.max(15f, scene.extent / 40f)
        )
        if (following) {
            // on the model, at the height it is flying
            renderer.target = floatArrayOf(
                scene.east(last.lon),
                last.altitudeMsl - scene.originAltitude,
                -scene.north(last.lat)
            )
        }

        // flown off the edge of the ground that was built: fetch more
        if (!loadingTerrain && scene.outsideLoaded(last.lat, last.lon)) {
            loadingTerrain = true
            status.text = "Loading more terrain…"
            val worker = Thread(Runnable {
                scene.loadTerrain(points, { _, _ -> }, { })
                runOnUiThread {
                    renderer.submit(scene.tiles)
                    status.text = ""
                    loadingTerrain = false
                }
            })
            worker.name = "terrain-extend"
            worker.start()
        }
    }

    /** A post where the phone is standing, so the ground has something familiar in it. */
    private fun showMyLocation() {
        val lat = intent.getDoubleExtra(EXTRA_MY_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_MY_LON, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return
        val ground = scene.groundAt(lat, lon) ?: return
        renderer.setMarker(
            scene.east(lon), ground - scene.originAltitude, -scene.north(lat), 40f
        )

        // The same accuracy ring the map draws, laid on the ground rather than
        // flat: on a slope a flat circle would float at one end and bury itself
        // at the other.
        val accuracy = intent.getFloatExtra(EXTRA_MY_ACCURACY, 0f)
        if (accuracy < 1f) return
        val metresPerDegreeLon = 111320.0 * Math.cos(Math.toRadians(lat))
        val ring = FloatArray(CIRCLE_SEGMENTS * 3)
        var i = 0
        for (step in 0 until CIRCLE_SEGMENTS) {
            val angle = 2.0 * Math.PI * step / CIRCLE_SEGMENTS
            val pointLat = lat + accuracy * Math.cos(angle) / 111320.0
            val pointLon = lon + accuracy * Math.sin(angle) / metresPerDegreeLon
            val h = scene.groundAt(pointLat, pointLon) ?: ground
            ring[i++] = scene.east(pointLon)
            // a metre up, so it is not fighting the ground it lies on
            ring[i++] = h - scene.originAltitude + 1f
            ring[i++] = -scene.north(pointLat)
        }
        renderer.setAccuracyCircle(ring)
    }

    /** Course over the ground, in degrees from north, which is which way it points. */
    private fun courseBetween(from: TerrainScene.TrackPoint, to: TerrainScene.TrackPoint): Float {
        val dx = (scene.east(to.lon) - scene.east(from.lon)).toDouble()
        val dz = (scene.north(to.lat) - scene.north(from.lat)).toDouble()
        if (dx == 0.0 && dz == 0.0) return 0f
        return Math.toDegrees(Math.atan2(dx, dz)).toFloat()
    }

    private fun heightOfTrack(): Float {
        var highest = 0f
        var i = 1
        while (i < scene.track.size) {
            if (scene.track[i] > highest) highest = scene.track[i]
            i += 3
        }
        return highest
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastTouchDown < 300) {
                    following = true
                    status.text = ""
                }
                lastTouchDown = now
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastSpan = spanOf(event)
                pinching = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    pinching = true
                    val span = spanOf(event)
                    if (lastSpan > 0f && span > 0f) {
                        renderer.distance =
                            (renderer.distance * lastSpan / span).coerceIn(50f, 200000f)
                    }
                    lastSpan = span

                    // two fingers moving together drag the ground under the
                    // camera, which is the only way to look somewhere else
                    val focusX = (event.getX(0) + event.getX(1)) / 2
                    val focusY = (event.getY(0) + event.getY(1)) / 2
                    if (lastFocusX != 0f || lastFocusY != 0f) {
                        panBy(focusX - lastFocusX, focusY - lastFocusY)
                    }
                    lastFocusX = focusX
                    lastFocusY = focusY
                } else if (!pinching) {
                    if (following) {
                        following = false
                        status.text = "Double tap to follow"
                    }
                    // a finger drags the world round, so the model stays put
                    renderer.azimuth -= (event.x - lastX) * 0.3f
                    renderer.elevation =
                        (renderer.elevation + (event.y - lastY) * 0.2f).coerceIn(3f, 87f)
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastSpan = 0f
                lastFocusX = 0f
                lastFocusY = 0f
                // the finger that stays becomes the new anchor, at wherever it
                // happens to be, so nothing snaps
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
            }
            MotionEvent.ACTION_UP -> pinching = false
        }
        return true
    }

    /**
     * Slide the camera's target across the ground.
     *
     * Scaled by how far out the camera is, so a finger covers the same ground
     * on screen whether you are looking at a field or a valley.
     */
    private fun panBy(dx: Float, dy: Float) {
        if (following) {
            following = false
            status.text = "Double tap to follow"
        }
        val height = Math.max(1, resources.displayMetrics.heightPixels)
        val metresPerPixel = renderer.distance * 0.93f / height
        val az = Math.toRadians(renderer.azimuth.toDouble())
        val rightX = Math.cos(az).toFloat()
        val rightZ = -Math.sin(az).toFloat()
        val awayX = Math.sin(az).toFloat()
        val awayZ = Math.cos(az).toFloat()
        val t = renderer.target
        renderer.target = floatArrayOf(
            t[0] - dx * metresPerPixel * rightX + dy * metresPerPixel * awayX,
            t[1],
            t[2] - dx * metresPerPixel * rightZ + dy * metresPerPixel * awayZ
        )
    }

    private fun spanOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        surface.onPause()
        handler.removeCallbacks(poll)
    }

    override fun onDestroy() {
        super.onDestroy()
        loader?.interrupt()
    }
}
