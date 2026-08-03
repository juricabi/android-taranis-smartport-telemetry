package crazydude.com.telemetry.ui

import android.content.Context
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import crazydude.com.telemetry.gl.LiveFlightPath
import crazydude.com.telemetry.gl.TerrainRenderer
import crazydude.com.telemetry.gl.TerrainScene
import crazydude.com.telemetry.utils.Elevation
import crazydude.com.telemetry.utils.Imagery

/**
 * The ground in three dimensions, as a view rather than a screen.
 *
 * It goes where the map goes, inside the same holder, so everything around it
 * — the telemetry, the connect button, the buttons down the side — stays
 * exactly where it was. Choosing 3D is choosing how to draw the ground, not
 * leaving the app.
 */
class Terrain3DView(context: Context) : FrameLayout(context) {

    companion object {
        private const val FOLLOW_INTERVAL_MS = 500L
        private const val CIRCLE_SEGMENTS = 64
    }

    private val surface = GLSurfaceView(context)
    private val renderer = TerrainRenderer()
    private val scene = TerrainScene()
    private val status = TextView(context)

    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastTouchDown = 0L
    private var pinching = false
    private var seenVersion = -1
    private var loadingTerrain = false
    private var started = false

    private var myLat = Double.NaN
    private var myLon = Double.NaN
    private var myAccuracy = 0f

    /**
     * Whether the camera rides the model. No mode to pick: with nothing
     * arriving it frames what was flown, and the moment a point comes in it
     * follows. A touch takes control, a double tap gives it back.
     */
    private var following = true

    private val ticker = Handler()
    private val poll = object : Runnable {
        override fun run() {
            pickUpNewPoints()
            ticker.postDelayed(this, FOLLOW_INTERVAL_MS)
        }
    }

    init {
        Elevation.init(context)
        Imagery.init(context)

        surface.setEGLContextClientVersion(2)
        surface.setRenderer(renderer)
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        addView(surface, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        status.setTextColor(Color.WHITE)
        status.setShadowLayer(4f, 0f, 0f, Color.BLACK)
        status.setPadding(24, 16, 24, 16)
        val params = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.gravity = Gravity.TOP or Gravity.START
        addView(status, params)

        isClickable = true
    }

    /**
     * Build the scene. [fallbackLat]/[fallbackLon] are used when nothing has
     * been flown yet, because the ground is worth looking at either way.
     */
    fun start(points: List<TerrainScene.TrackPoint>,
              fallbackLat: Double, fallbackLon: Double,
              myLatitude: Double, myLongitude: Double, accuracy: Float) {
        if (started) return
        started = true
        myLat = myLatitude
        myLon = myLongitude
        myAccuracy = accuracy

        val hasFlight = points.size >= 2 && scene.setTrack(points)
        if (!hasFlight) {
            if (fallbackLat.isNaN() || fallbackLon.isNaN()) {
                status.text = "No position yet"
                return
            }
            scene.setOrigin(fallbackLat, fallbackLon, 0f)
        }
        // fixed from here: the ground is built in this frame, so re-centring
        // later would slide the terrain out from under the flight
        scene.setOrigin(scene.originLat, scene.originLon, scene.originAltitude)
        val flight = if (hasFlight) points else emptyList()

        renderer.setTrack(scene.track, scene.shadow)
        renderer.groundUnderCamera = { x, z ->
            val lat = scene.originLat - z / 111320.0
            val lon = scene.originLon + x / (111320.0 * Math.cos(Math.toRadians(scene.originLat)))
            val h = scene.groundAt(lat, lon)
            if (h == null) null else h - scene.originAltitude
        }
        renderer.target = floatArrayOf(0f, heightOfTrack() / 2f, 0f)
        renderer.distance = Math.max(600f, scene.extent * 3f)
        status.text = "Loading terrain…"

        val worker = Thread(Runnable {
            scene.loadTerrain(flight,
                { done, total -> post { status.text = "Loading terrain… $done of $total" } },
                { post { renderer.setTrack(scene.track, scene.shadow) } })
            post {
                renderer.submit(scene.tiles)
                renderer.setTrack(scene.track, scene.shadow)
                showMyLocation()
                status.text = if (scene.tiles.isEmpty()) "No terrain here" else ""
                loadingTerrain = false
            }
        })
        worker.name = "terrain-load"
        worker.start()
        ticker.post(poll)
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

    private fun pickUpNewPoints() {
        val version = LiveFlightPath.version
        if (version == seenVersion) return
        seenVersion = version
        val points = LiveFlightPath.snapshot()
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
            renderer.target = floatArrayOf(
                scene.east(last.lon),
                last.altitudeMsl - scene.originAltitude,
                -scene.north(last.lat)
            )
        }

        if (!loadingTerrain && scene.nearEdge(last.lat, last.lon)) {
            loadingTerrain = true
            status.text = "Loading more terrain…"
            val worker = Thread(Runnable {
                scene.loadTerrain(points, { _, _ -> }, { })
                post {
                    renderer.submit(scene.tiles)
                    status.text = ""
                    loadingTerrain = false
                }
            })
            worker.name = "terrain-extend"
            worker.start()
        }
    }

    private fun courseBetween(from: TerrainScene.TrackPoint, to: TerrainScene.TrackPoint): Float {
        val dx = (scene.east(to.lon) - scene.east(from.lon)).toDouble()
        val dz = (scene.north(to.lat) - scene.north(from.lat)).toDouble()
        if (dx == 0.0 && dz == 0.0) return 0f
        return Math.toDegrees(Math.atan2(dx, dz)).toFloat()
    }

    /** The same arrow and accuracy ring the map draws, laid on the ground. */
    private fun showMyLocation() {
        if (myLat.isNaN() || myLon.isNaN()) return
        val ground = scene.groundAt(myLat, myLon) ?: return
        val x = scene.east(myLon)
        val z = -scene.north(myLat)
        val y = ground - scene.originAltitude + 1f
        val size = Math.max(12f, scene.extent / 50f)

        // a chevron lying on the ground, the shape the map uses, rather than the
        // bare post that was here before
        renderer.setMyLocation(floatArrayOf(
            x, y, z - size,
            x - size * 0.7f, y, z + size * 0.7f,
            x, y, z + size * 0.2f,
            x, y, z - size,
            x, y, z + size * 0.2f,
            x + size * 0.7f, y, z + size * 0.7f
        ))

        if (myAccuracy < 1f) return
        val metresPerDegreeLon = 111320.0 * Math.cos(Math.toRadians(myLat))
        val ring = FloatArray(CIRCLE_SEGMENTS * 3)
        var i = 0
        for (step in 0 until CIRCLE_SEGMENTS) {
            val angle = 2.0 * Math.PI * step / CIRCLE_SEGMENTS
            val pointLat = myLat + myAccuracy * Math.cos(angle) / 111320.0
            val pointLon = myLon + myAccuracy * Math.sin(angle) / metresPerDegreeLon
            val h = scene.groundAt(pointLat, pointLon) ?: ground
            ring[i++] = scene.east(pointLon)
            ring[i++] = h - scene.originAltitude + 1f
            ring[i++] = -scene.north(pointLat)
        }
        renderer.setAccuracyCircle(ring)
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
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
            }
            MotionEvent.ACTION_UP -> pinching = false
        }
        return true
    }

    /** Drag the ground with the fingers, scaled by how far out the camera is. */
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
        // the ground goes the way the fingers go, which is the other way from
        // the camera
        renderer.target = floatArrayOf(
            t[0] + dx * metresPerPixel * rightX - dy * metresPerPixel * awayX,
            t[1],
            t[2] + dx * metresPerPixel * rightZ - dy * metresPerPixel * awayZ
        )
    }

    private fun spanOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    fun onResume() {
        surface.onResume()
        ticker.post(poll)
    }

    fun onPause() {
        surface.onPause()
        ticker.removeCallbacks(poll)
    }

    fun release() {
        ticker.removeCallbacks(poll)
    }
}
