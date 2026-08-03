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
class Terrain3DView(context: Context) : FrameLayout(context), android.hardware.SensorEventListener {

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
    private var lastFocusY = 0f
    private var lastAngle = 0f
    private var seenVersion = -1
    private var loadingTerrain = false
    private var started = false

    private var myLat = Double.NaN
    private var myLon = Double.NaN
    private var myAccuracy = 0f
    private var myHeading = 0f

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
        // the context survives leaving the app on most devices, which avoids a
        // rebuild; the renderer can put the meshes back either way
        surface.preserveEGLContextOnPause = true
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
        // close enough to make out the ground; the whole flight if there is one
        renderer.distance = if (hasFlight) {
            Math.max(400f, scene.extent * 2.2f)
        } else {
            500f
        }
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
        // Where the nose is pointing when the model says so, since that differs
        // from the course over the ground in any wind; the course is the
        // fallback for links that carry no attitude.
        val heading = if (hasAttitude) modelHeading else courseBetween(before, last)
        renderer.setModel(
            scene.east(last.lon),
            last.altitudeMsl - scene.originAltitude,
            -scene.north(last.lat),
            heading,
            Math.max(15f, scene.extent / 40f),
            modelPitch, modelRoll
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
        placeMyArrow()

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

    /** The buttons down the side of the map drive this too. */
    fun setFollowing(on: Boolean) {
        following = on
        status.text = ""
        if (on) LiveFlightPath.latest()?.let { lookAt(it.lat, it.lon, it.altitudeMsl) }
    }

    fun isFollowing(): Boolean = following

    fun faceNorth() {
        renderer.azimuth = 0f
        renderer.elevation = 30f
    }

    /** Put the camera on a place, without following anything. */
    fun lookAt(lat: Double, lon: Double, altitudeMsl: Float?) {
        val ground = scene.groundAt(lat, lon)
        val height = when {
            altitudeMsl != null -> altitudeMsl - scene.originAltitude
            ground != null -> ground - scene.originAltitude
            else -> 0f
        }
        renderer.target = floatArrayOf(scene.east(lon), height, -scene.north(lat))
    }

    fun goToMyLocation(): Boolean {
        if (myLat.isNaN() || myLon.isNaN()) return false
        following = false
        status.text = ""
        lookAt(myLat, myLon, null)
        renderer.distance = Math.min(renderer.distance, 800f)
        return true
    }

    /**
     * The gestures every map app uses, because everyone already knows them:
     * one finger drags the ground, two fingers pinch to zoom, twist to turn,
     * and slide together to tilt.
     */
    private var hasAttitude = false
    private var modelHeading = 0f
    private var modelPitch = 0f
    private var modelRoll = 0f

    /** The model's own attitude, which is worth far more than its shape. */
    fun setModelAttitude(heading: Float, pitch: Float, roll: Float) {
        hasAttitude = true
        modelHeading = heading
        modelPitch = pitch
        modelRoll = roll
    }

    /** Which way the phone is pointing, so the arrow means something. */
    fun setMyHeading(degrees: Float) {
        var turn = degrees - myHeading
        while (turn > 180f) turn -= 360f
        while (turn < -180f) turn += 360f
        // a couple of degrees of hysteresis, or it twitches on every sample
        if (Math.abs(turn) < 2f) return
        myHeading = degrees
        placeMyArrow()
    }

    private fun placeMyArrow() {
        if (myLat.isNaN() || myLon.isNaN()) return
        val ground = scene.groundAt(myLat, myLon) ?: return
        renderer.setMyLocation(
            scene.east(myLon), ground - scene.originAltitude + 1f,
            -scene.north(myLat), myHeading
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastSpan = spanOf(event)
                lastAngle = angleOf(event)
                lastFocusY = focusYOf(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val span = spanOf(event)
                    val angle = angleOf(event)
                    val focusY = focusYOf(event)

                    if (lastSpan > 0f && span > 0f) {
                        renderer.distance =
                            (renderer.distance * lastSpan / span).coerceIn(50f, 200000f)
                    }
                    // a twist turns the world, the way it does on a map
                    var turn = angle - lastAngle
                    while (turn > 180f) turn -= 360f
                    while (turn < -180f) turn += 360f
                    if (Math.abs(turn) < 40f) renderer.azimuth += turn

                    // both fingers sliding together tilt the view
                    val tilt = focusY - lastFocusY
                    if (Math.abs(tilt) > 0.5f) {
                        renderer.elevation =
                            (renderer.elevation + tilt * 0.15f).coerceIn(3f, 87f)
                        followingOff()
                    }

                    lastSpan = span
                    lastAngle = angle
                    lastFocusY = focusY
                } else {
                    panBy(event.x - lastX, event.y - lastY)
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastSpan = 0f
                // the finger that stays becomes the anchor, wherever it is, so
                // nothing snaps when the other one leaves
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
            }
        }
        return true
    }

    /** Drag the ground with the finger, scaled by how far out the camera is. */
    private fun panBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        followingOff()
        val height = Math.max(1, resources.displayMetrics.heightPixels)
        val metresPerPixel = renderer.distance * 0.93f / height
        val az = Math.toRadians(renderer.azimuth.toDouble())
        val rightX = Math.cos(az).toFloat()
        val rightZ = -Math.sin(az).toFloat()
        val awayX = Math.sin(az).toFloat()
        val awayZ = Math.cos(az).toFloat()
        val t = renderer.target
        // the ground follows the finger, so the camera goes the other way, in
        // both axes — the vertical one was inverted, which is what made
        // dragging feel wrong however the horizontal was set
        renderer.target = floatArrayOf(
            t[0] - dx * metresPerPixel * rightX - dy * metresPerPixel * awayX,
            t[1],
            t[2] - dx * metresPerPixel * rightZ - dy * metresPerPixel * awayZ
        )
    }

    /** Touching the view takes the camera; the follow button gives it back. */
    private fun followingOff() {
        if (!following) return
        following = false
        onFollowingLost?.invoke()
    }

    /** Told to the map screen, so its follow button can dim with the mode. */
    var onFollowingLost: (() -> Unit)? = null

    private fun angleOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private fun focusYOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return (event.getY(0) + event.getY(1)) / 2
    }

    private fun spanOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    // The compass, so the arrow points where the phone is pointing. Same
    // sensors the map's own arrow uses, read straight rather than through
    // osmdroid, since nothing here is an osmdroid overlay.
    private val sensors =
        context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager?
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        when (event.sensor.type) {
            android.hardware.Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
                hasGravity = true
            }
            android.hardware.Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                hasGeomagnetic = true
            }
        }
        if (!hasGravity || !hasGeomagnetic) return
        val r = FloatArray(9)
        if (!android.hardware.SensorManager.getRotationMatrix(r, null, gravity, geomagnetic)) return
        val orientation = FloatArray(3)
        android.hardware.SensorManager.getOrientation(r, orientation)
        var degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (degrees < 0) degrees += 360f
        setMyHeading(degrees)
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

    /**
     * Sensors follow the view being on screen, not the activity's lifecycle.
     *
     * This view is built when 3D is chosen, which is long after the activity
     * resumed — so registering in onResume alone meant the compass was never
     * listened to at all and the arrow never turned.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        listenToCompass()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sensors?.unregisterListener(this)
    }

    private fun listenToCompass() {
        sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensors.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun onResume() {
        surface.onResume()
        ticker.post(poll)
        listenToCompass()
    }

    fun onPause() {
        surface.onPause()
        ticker.removeCallbacks(poll)
        sensors?.unregisterListener(this)
    }

    fun release() {
        ticker.removeCallbacks(poll)
    }
}
