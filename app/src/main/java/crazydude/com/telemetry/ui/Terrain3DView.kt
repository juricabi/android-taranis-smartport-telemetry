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

    /** The ground is up: until it is, there is nothing to draw anything on. */
    private var terrainReady = false

    /**
     * Whether a flight belongs on screen. Set when one is handed in at the
     * start, and when a fix arrives afterwards — so a finished flight left in
     * memory is not brought back the moment the ground finishes loading.
     */
    private var flightShown = false

    /** Plans already laid on the ground, by the plan they were laid from. */
    private val drapedPlans =
        HashMap<Pair<List<crazydude.com.telemetry.maps.Position>, Int>, TerrainRenderer.LineSet>()
    private var started = false

    private var myLat = Double.NaN
    private var myLon = Double.NaN
    private var myAccuracy = 0f

    /** The last ground height known under this phone, for a step past the edge. */
    private var myGround = Float.NaN
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
            // Opened with nowhere to stand — no fix from the model, none from
            // the phone. Rather than stay black until the map type is changed
            // and back, start as soon as either turns up. A flight only counts
            // if it is live: one left in memory from earlier is not a reason.
            if (!started) {
                val live = if (flightShown) LiveFlightPath.latest() else null
                when {
                    live != null -> start(LiveFlightPath.snapshot(), live.lat, live.lon,
                        myLat, myLon, myAccuracy)
                    !myLat.isNaN() && !myLon.isNaN() ->
                        start(emptyList(), myLat, myLon, myLat, myLon, myAccuracy)
                }
            }
            pickUpNewPoints()
            // the camera turns itself while chasing, so the heading in the
            // corner is read off it rather than told to it
            if (chasing) onBearingChanged?.invoke(renderer.azimuth)
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
        // A flight handed over at the start is a flight to draw, as much as one
        // that arrives afterwards. Waiting for a fix meant that switching to
        // this view while a replay was paused showed the ground and nothing on
        // it until playback was started again.
        if (hasFlight) flightShown = true
        if (!hasFlight) {
            if (fallbackLat.isNaN() || fallbackLon.isNaN()) {
                status.text = "No position yet"
                // Not started, so nothing is loading and nothing will draw. The
                // tick still runs: the first fix to arrive starts the ground.
                started = false
                ticker.post(poll)
                return
            }
            scene.setOrigin(fallbackLat, fallbackLon, 0f)
        }
        // fixed from here: the ground is built in this frame, so re-centring
        // later would slide the terrain out from under the flight
        scene.setOrigin(scene.originLat, scene.originLon, scene.originAltitude)
        val flight = if (hasFlight) points else emptyList()

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
        // Zooming out past the ground shows nothing but sky, so the limit
        // follows the flight: a couple of kilometres for an ordinary one, more
        // for a flight that covers more.
        renderer.maxDistance = Math.max(2500f, scene.extent * 5f)
        // no notice that it is loading: the empty screen says so already

        // the ground gathers around the model, or around here when nothing is
        // flying yet
        val focusLat = if (hasFlight) points[points.size - 1].lat else scene.originLat
        val focusLon = if (hasFlight) points[points.size - 1].lon else scene.originLon

        val worker = Thread(Runnable {
            try {
            scene.loadTerrain(flight, focusLat, focusLon,
                { post { groundArrived() } },
                { post { groundArrived(true); loadingTerrain = false } })
            } catch (e: Throwable) {
                // Whatever went wrong out here — no signal, a tile that would
                // not decode, memory — the ground is as ready as it is ever
                // going to be. Left false, nothing would ever draw again and
                // the screen would stay black with nothing said.
                post {
                    terrainReady = true
                    loadingTerrain = false
                    rebuildOverlays()
                }
            }
        })
        worker.name = "terrain-load"
        worker.start()
        ticker.post(poll)
    }

    /**
     * A tile has landed, or the last of them has.
     *
     * The ground is built one tile at a time and shown as each is finished, so
     * this runs many times over a load: it hands the renderer whatever is built
     * and tells it what to keep. Everything that was waiting on ground to stand
     * on goes on the first one, not the last — that is the difference between a
     * black screen for several seconds and terrain under the model at once.
     */
    private fun groundArrived(whateverHasCome: Boolean = false) {
        val meshes = scene.tiles
        // The flight waits for the ground to have its picture.
        //
        // The shape of the ground is built first and shown as it comes, which
        // is what puts something on the screen quickly. But a model flying
        // across bare grey mesh, with its whole path drawn over it, is a worse
        // thing to watch than a moment of empty screen — so the flight, the
        // model and the lines hold until at least one tile has its photograph.
        // Once the loading has finished they are shown whatever came of it, or
        // somewhere with no imagery at all would never show a flight.
        val dressed = whateverHasCome || meshes.any { it.texture != null }
        val first = !terrainReady && dressed
        if (dressed) terrainReady = true
        val keys = HashSet<Long>()
        for (mesh in meshes) keys.add(mesh.key)
        renderer.keepOnly(keys)
        for (mesh in meshes) renderer.offer(mesh)
        if (first) {
            seenVersion = -1
            renderer.setTrack(scene.track, scene.shadow)
            pickUpNewPoints()
            // Arrive at the model rather than travel to it. The camera is aimed
            // at the middle of the scene until the ground is up and the flight
            // can be placed, and easing from one to the other is a second or
            // two of the whole world sliding past while the view opens.
            renderer.snapToTarget()
            // and the plans and traffic in their own right: they are worth
            // seeing over bare ground, and with nothing flying the flight above
            // would never have drawn them at all
            rebuildOverlays()
            showMyLocation()
        }
        status.text = if (meshes.isEmpty()) "No terrain here" else ""
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

    /**
     * The flight has been thrown away — a replay has jumped somewhere else.
     *
     * The path is rebuilt on the tick, so without this the old flight went on
     * being drawn for up to half a second after the jump while the model was
     * already somewhere else entirely, which is a glitch nobody can explain to
     * themselves.
     */
    fun onFlightReset() {
        seenVersion = -1
        appendedThrough = 0
        lastAppendedPoint = null
        placedOnce = false
        renderer.setTrack(FloatArray(0), FloatArray(0))
        // and everything drawn from where the model was. These are refreshed on
        // the tick, so left alone they went on being drawn for up to half a
        // second after the flight beneath them had gone.
        renderer.hideModel()
        renderer.setHomeLine(false, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        renderer.setHeadingLine(false, 0f, 0f, 0f, 0f)
    }

    /** How much of the flight has been handed to the renderer point by point. */
    private var appendedThrough = 0

    /** The one before it, to tell which way the model is going without attitude. */
    private var lastAppendedPoint: TerrainScene.TrackPoint? = null

    /** Whether the model has been put somewhere yet, for the camera to arrive at. */
    private var placedOnce = false

    /**
     * Told when a fix lands, so the model moves with the one on the map rather
     * than up to a tick behind it.
     *
     * Only the model. Rebuilding the flight, its curtain and every overlay is
     * the tick's work: doing it here meant doing it for every fix, and a replay
     * — which delivers a whole flight in a minute — left no time on the main
     * thread for the buttons that were meant to control it.
     */
    fun onNewPoint() {
        // Noted whether or not there is anywhere to draw it yet: this is what
        // tells a view that opened with nothing that a flight is under way.
        flightShown = true
        if (!started || !terrainReady) return
        // The flight grows with the model rather than waiting for the tick, so
        // the line, its shadow and the curtain between them always end where
        // the model is.
        //
        // Every point that has arrived, not just the newest: a replay hands
        // over a batch at a time, and taking only the last of each drew one
        // long straight piece of flight — and one long piece of curtain under
        // it — across the whole batch, until the tick came round and replaced
        // it with the real shape.
        var arrived = 0
        for (point in LiveFlightPath.since(appendedThrough)) {
            // Which way it is pointing, from one point to the next, for links
            // that carry no attitude. It was worked out on the tick, and the
            // tick does nothing when no new points are arriving — so a replay
            // paused just after a jump left the model facing whichever way it
            // had been facing before the jump.
            if (!hasAttitude) {
                lastAppendedPoint?.let { lastModelHeading = courseBetween(it, point) }
            }
            lastAppendedPoint = point
            renderer.appendFlightPoint(
                scene.east(point.lon),
                scene.aboveSeaLevel(point.altitudeMsl) - scene.originAltitude,
                -scene.north(point.lat),
                (scene.groundAt(point.lat, point.lon) ?: scene.originAltitude) -
                    scene.originAltitude)
            appendedThrough++
            arrived++
        }
        // The model last, so it is placed with the course worked out from the
        // points that have just arrived. Placed first it was set facing
        // whichever way it had been facing before them — and with a replay
        // paused after a jump nothing came along to place it again.
        placeModel()
        // A batch at a time is a replay, not a flight: the model belongs at the
        // end of it rather than walking through it while the flight it is
        // supposed to be at the end of is already drawn past it.
        if (arrived > 1) renderer.snapToTarget()
    }

    /** Where the model is now, from the newest point; cheap enough for every fix. */
    private fun placeModel() {
        val last = LiveFlightPath.latest() ?: return
        // the nose when the model says so, and otherwise the course the tick
        // last worked out from the path
        if (hasAttitude) lastModelHeading = modelHeading
        applyChaseBearing()
        val x = scene.east(last.lon)
        val y = scene.aboveSeaLevel(last.altitudeMsl) - scene.originAltitude
        val z = -scene.north(last.lat)
        renderer.setModel(x, y, z, lastModelHeading,
            Math.max(15f, scene.extent / 40f), modelPitch, modelRoll)
        if (following) {
            renderer.target = floatArrayOf(x + panX, y, z + panZ)
        }
        // The first time the model is put anywhere, the camera arrives at it
        // rather than easing to it from wherever it was aimed while the ground
        // was still loading — which is a second or two of the world sliding
        // past as the view opens. Tying this to the first tile of ground was
        // not enough: there is often no flight to place yet when that lands.
        if (!placedOnce) {
            placedOnce = true
            renderer.snapToTarget()
        }
        // With the model, not half a second behind it: the line home and the
        // line ahead both start where it is, and the plans they are drawn
        // beside are laid out once and kept, so this is a few vertices now.
        rebuildOverlays()
    }

    private fun pickUpNewPoints() {
        if (!terrainReady || !flightShown) return
        val version = LiveFlightPath.version
        if (version == seenVersion) return
        seenVersion = version
        val points = LiveFlightPath.snapshot()
        if (points.size < 2) return

        // Before the track is built from them: this settles what their heights
        // mean, and the track is laid out in that answer.
        val frameMoved = scene.resolveAltitudeIfNeeded(points)
        scene.buildTrack(points)
        renderer.setTrack(scene.track, scene.shadow)
        // rebuilt from the whole flight, so everything is accounted for again
        appendedThrough = points.size

        val last = points[points.size - 1]
        val before = points[Math.max(0, points.size - 4)]
        // Where the nose is pointing when the model says so, since that differs
        // from the course over the ground in any wind; the course is the
        // fallback for links that carry no attitude.
        if (!hasAttitude) lastModelHeading = courseBetween(before, last)
        placeModel()

        // The ground does not move when this is settled — only the flight does —
        // so no tile has to be built again. What does have to go is anything
        // laid out at the old heights and kept: the plans were draped once and
        // would otherwise be left hanging where the flight used to be.
        if (frameMoved) {
            drapedPlans.clear()
            // the flight has just been lifted onto its proper height, so the
            // camera goes with it rather than drifting up after it
            renderer.snapToTarget()
        }
        extendTerrainIfNeeded(points, last.lat, last.lon)
    }

    /**
     * More ground, when something that has to stand on it nears the edge.
     *
     * The model, and this phone as well: driving to the far side of a field
     * with the view open used to take the arrow past the loaded ground, where
     * it stopped dead — no more terrain was asked for, because only the flight
     * was being watched.
     *
     * Not the camera, deliberately. Panning across the county would pull tiles
     * for wherever it was pointed and evict the ones the flight is on.
     */
    private fun extendTerrainIfNeeded(points: List<TerrainScene.TrackPoint>,
                                      lat: Double, lon: Double, force: Boolean = false) {
        if (!loadingTerrain && (force || scene.nearEdge(lat, lon))) {
            loadingTerrain = true
            status.text = ""
            val worker = Thread(Runnable {
                scene.loadTerrain(points, lat, lon,
                    { post { groundArrived() } },
                    { post {
                        groundArrived(true)
                        renderer.maxDistance = Math.max(2500f, scene.extent * 5f)
                        loadingTerrain = false
                    } })
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
    /** Where the phone is, as it changes. Keeps the arrow and its ring current. */
    fun setMyPosition(lat: Double, lon: Double, accuracy: Float) {
        myLat = lat
        myLon = lon
        myAccuracy = accuracy
        showMyLocation()
        // With nothing flying, this is the only thing that can walk off the
        // edge of the loaded ground. While something is flying, the ground
        // gathers around that instead — it cannot follow both at once, and the
        // model is the one being watched.
        if (started && terrainReady && LiveFlightPath.size() < 2) {
            extendTerrainIfNeeded(LiveFlightPath.snapshot(), lat, lon)
        }
    }

    private fun showMyLocation() {
        placeMyArrow()
    }

    /** The buttons down the side of the map drive this too. */
    fun setFollowing(on: Boolean) {
        following = on
        status.text = ""
        // there is no riding behind something that is not being kept up with
        if (!on) chasing = false
        if (on) {
            panX = 0f
            panZ = 0f
            LiveFlightPath.latest()?.let { lookAt(it.lat, it.lon, it.altitudeMsl) }
        }
    }

    fun isFollowing(): Boolean = following

    fun faceNorth() {
        // north up and behind the model are two different answers to the same
        // question, so asking for one lets go of the other
        chasing = false
        renderer.chasingModel = false
        renderer.azimuthWanted = Float.NaN
        renderer.azimuth = 0f
        renderer.elevation = 30f
        onBearingChanged?.invoke(renderer.azimuth)
    }

    /**
     * Which way the camera looks, whenever it turns. The map reports the same
     * thing as it is rotated, and the heading in the corner is drawn from it.
     */
    var onBearingChanged: ((Float) -> Unit)? = null

    /** Where the camera is pointing now, for whoever has just started listening. */
    fun bearing(): Float = renderer.azimuth

    /** Put the camera on a place, without following anything. */
    fun lookAt(lat: Double, lon: Double, altitudeMsl: Float?) {
        val ground = scene.groundAt(lat, lon)
        val height = when {
            // through the same reference as the model, or following it aimed
            // the camera at a point buried under the hill it is flying over
            altitudeMsl != null -> scene.aboveSeaLevel(altitudeMsl) - scene.originAltitude
            ground != null -> ground - scene.originAltitude
            else -> 0f
        }
        renderer.target = floatArrayOf(scene.east(lon), height, -scene.north(lat))
    }

    fun goToMyLocation(): Boolean {
        if (myLat.isNaN() || myLon.isNaN()) return false
        // asked for somewhere else entirely, which is a different thing from
        // leaning out of the chase
        followingOff()
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

    // ------------------------------------------------- what the map also draws

    private var flightPlans: List<Pair<List<crazydude.com.telemetry.maps.Position>, Int>> =
        emptyList()
    private var traffic: List<crazydude.com.telemetry.manager.Fr24Manager.AirplaneInfo> = emptyList()
    private var homeLineOn = false
    private var headingLineOn = false
    private var homeLineColor = 0
    private var headingLineColor = 0

    fun setOverlaySettings(homeLine: Boolean, homeColor: Int,
                           headingLine: Boolean, headingColor: Int) {
        homeLineOn = homeLine
        homeLineColor = homeColor
        headingLineOn = headingLine
        headingLineColor = headingColor
        rebuildOverlays()
    }

    fun setFlightPlans(plans: List<Pair<List<crazydude.com.telemetry.maps.Position>, Int>>) {
        flightPlans = plans
        rebuildOverlays()
    }

    /** Real aircraft, at the altitude they are actually flying at. */
    fun setTraffic(airplanes: List<crazydude.com.telemetry.manager.Fr24Manager.AirplaneInfo>) {
        traffic = airplanes
        rebuildOverlays()
    }

    private fun colorOf(argb: Int): FloatArray = floatArrayOf(
        ((argb shr 16) and 0xFF) / 255f,
        ((argb shr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
        Math.max(0.35f, ((argb ushr 24) and 0xFF) / 255f))

    private fun rebuildOverlays() {
        // flight plans and traffic arrive on their own schedule, and they wait
        // for the ground as everything else does
        if (!terrainReady) return
        val sets = ArrayList<TerrainRenderer.LineSet>()
        val model = LiveFlightPath.latest()

        // the line home, which on a map goes to the phone
        // Both of these start at the model, so the renderer draws them from
        // where it is drawing the model. Given as vertices here they jumped to
        // each fix and then waited, while the model glided between them.
        val home = if (myLat.isNaN() || myLon.isNaN()) null else scene.groundAt(myLat, myLon)
        if (homeLineOn && model != null && home != null) {
            val c = colorOf(homeLineColor)
            renderer.setHomeLine(true, scene.east(myLon), home - scene.originAltitude,
                -scene.north(myLat), c[0], c[1], c[2], c[3])
        } else {
            renderer.setHomeLine(false, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        // The last stretch, from where the track was last built to where the
        // model is now.
        //
        // The track itself is rebuilt twice a second — it is the whole path, and
        // doing that for every fix is what left no room for anything else — but
        // the model moves on every one of them, so its line was trailing half a
        // second behind it. Two vertices close the gap.
        // where it is heading, a kilometre of it
        if (headingLineOn && model != null && hasAttitude) {
            val c = colorOf(headingLineColor)
            renderer.setHeadingLine(true, c[0], c[1], c[2], c[3])
        } else {
            renderer.setHeadingLine(false, 0f, 0f, 0f, 0f)
        }

        // Imported plans, draped over the ground they cross, each in its own
        // colour. Only the corners are given, and a straight line between two of
        // them a kilometre apart passes under every rise in between — which is
        // what buried the plan whenever the camera came down near the horizon.
        for (entry in flightPlans) {
            val plan = entry.first
            if (plan.size < 2) continue
            val planColor = colorOf(entry.second)
            val already = drapedPlans[entry]
            if (already != null) {
                sets.add(already)
                continue
            }
            val draped = ArrayList<Float>(plan.size * 30)

            fun layOnGround(lat: Double, lon: Double) {
                val ground = scene.groundAt(lat, lon)
                draped.add(scene.east(lon))
                draped.add((ground ?: scene.originAltitude) - scene.originAltitude)
                draped.add(-scene.north(lat))
            }

            layOnGround(plan[0].lat, plan[0].lon)
            for (leg in 1 until plan.size) {
                val from = plan[leg - 1]
                val to = plan[leg]
                val dx = scene.east(to.lon) - scene.east(from.lon)
                val dz = scene.north(to.lat) - scene.north(from.lat)
                val length = Math.sqrt((dx * dx + dz * dz).toDouble())
                // a point every twenty metres or so, which is about as fine as
                // the ground itself is known
                val steps = Math.min(128, Math.max(1, Math.round(length / 20.0).toInt()))
                for (step in 1..steps) {
                    val part = step.toDouble() / steps
                    layOnGround(from.lat + (to.lat - from.lat) * part,
                        from.lon + (to.lon - from.lon) * part)
                }
            }

            val points = FloatArray(draped.size)
            for (i in draped.indices) points[i] = draped[i]
            // The ground it is laid on does not change between ticks, and
            // walking every leg twice a second is work for nothing.
            val set = TerrainRenderer.LineSet(points, planColor[0], planColor[1], planColor[2],
                planColor[3], true, 4f, true)
            drapedPlans[entry] = set
            sets.add(set)
        }

        // Traffic, at the height it is actually flying: a post from the ground
        // up to the aircraft, which is the thing a flat map cannot show you.
        if (traffic.isNotEmpty()) {
            val posts = FloatArray(traffic.size * 6)
            val marks = FloatArray(traffic.size * 12)
            var p = 0
            var m = 0
            for (plane in traffic) {
                val lat = plane.lat.toDouble()
                val lon = plane.lon.toDouble()
                val ground = scene.groundAt(lat, lon) ?: scene.originAltitude
                val x = scene.east(lon)
                val z = -scene.north(lat)
                val top = plane.altMeters - scene.originAltitude
                posts[p++] = x; posts[p++] = ground - scene.originAltitude; posts[p++] = z
                posts[p++] = x; posts[p++] = top.toFloat(); posts[p++] = z
                val arm = Math.max(40f, scene.extent / 12f)
                marks[m++] = x - arm; marks[m++] = top.toFloat(); marks[m++] = z
                marks[m++] = x + arm; marks[m++] = top.toFloat(); marks[m++] = z
                marks[m++] = x; marks[m++] = top.toFloat(); marks[m++] = z - arm
                marks[m++] = x; marks[m++] = top.toFloat(); marks[m++] = z + arm
            }
            sets.add(TerrainRenderer.LineSet(posts, 1f, 1f, 1f, 0.35f, false, 2f, false))
            sets.add(TerrainRenderer.LineSet(marks, 1f, 0.6f, 0.1f, 0.95f, false, 3f, false))
        }

        renderer.setOverlays(sets)
    }

    /** The model's colour, from the same setting the map marker uses. */
    fun setModelColor(argb: Int) {
        val c = colorOf(argb)
        renderer.modelColor = floatArrayOf(c[0], c[1], c[2], 1f)
    }

    /** The route line colour, so the flight looks the same in both views. */
    fun setTrackColor(argb: Int) {
        val c = colorOf(argb)
        renderer.trackColor = floatArrayOf(c[0], c[1], c[2], 1f)
    }

    /** Quad or plane, from the setting; the map marker follows the same one. */
    fun setModelShape(shape: String) {
        renderer.modelShape = shape
    }

    /** The model's own attitude, which is worth far more than its shape. */
    fun setModelAttitude(heading: Float, pitch: Float, roll: Float) {
        if (chasing) {
            lastModelHeading = heading
            applyChaseBearing()
        }
        hasAttitude = true
        modelHeading = heading
        modelPitch = pitch
        modelRoll = roll
    }

    /** Which way the phone is pointing, so the arrow means something. */
    fun setMyHeading(degrees: Float) {
        myHeading = degrees
        placeMyArrow()
    }

    /**
     * The arrow and its ring, together.
     *
     * They have to be built in the same breath: the ring used to be worked out
     * once, when the ground arrived, while the arrow was rebuilt on every
     * compass sample — so when the altitude reference settled and moved the
     * origin, the arrow followed and the ring was left hanging where it was.
     */
    private fun placeMyArrow() {
        if (!terrainReady || myLat.isNaN() || myLon.isNaN()) return
        // Where the ground is not known yet — a step past the edge of what is
        // loaded, while more is on its way — the last height it stood at will
        // do. It used to give up here, which left the arrow behind at the last
        // place it knew and turned it into a lie.
        val ground = scene.groundAt(myLat, myLon) ?: myGround
        if (ground.isNaN()) return
        myGround = ground
        renderer.setMyLocation(
            scene.east(myLon), ground - scene.originAltitude + 0.1f,
            -scene.north(myLat), myHeading
        )

        // an unknown accuracy is not a small one: drop the ring rather than
        // leave the last one it had lying there
        if (myAccuracy < 1f) {
            renderer.setAccuracyCircle(FloatArray(0))
            return
        }
        val metresPerDegreeLon = 111320.0 * Math.cos(Math.toRadians(myLat))
        val ring = FloatArray(CIRCLE_SEGMENTS * 3)
        var i = 0
        for (step in 0 until CIRCLE_SEGMENTS) {
            val angle = 2.0 * Math.PI * step / CIRCLE_SEGMENTS
            val pointLat = myLat + myAccuracy * Math.cos(angle) / 111320.0
            val pointLon = myLon + myAccuracy * Math.sin(angle) / metresPerDegreeLon
            val h = scene.groundAt(pointLat, pointLon) ?: ground
            ring[i++] = scene.east(pointLon)
            ring[i++] = h - scene.originAltitude + 0.15f
            ring[i++] = -scene.north(pointLat)
        }
        renderer.setAccuracyCircle(ring)
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
                        renderer.distance = (renderer.distance * lastSpan / span)
                            .coerceIn(60f, renderer.maxDistance)
                    }
                    // a twist turns the world, the way it does on a map
                    var turn = angle - lastAngle
                    while (turn > 180f) turn -= 360f
                    while (turn < -180f) turn += 360f
                    if (Math.abs(turn) < 40f) {
                        if (chasing) {
                            chaseYaw += turn
                            applyChaseBearing()
                        } else {
                            renderer.azimuth += turn
                            onBearingChanged?.invoke(renderer.azimuth)
                        }
                    }

                    // both fingers sliding together tilt the view
                    // Turning, tilting and zooming all move the camera round
                    // what it is looking at, and leave that alone — so they do
                    // not give up following it. Only dragging does, because
                    // that is what takes the camera somewhere else. A twist
                    // always slides the focus a little, which is why rotating
                    // used to stop the chase.
                    val tilt = focusY - lastFocusY
                    if (Math.abs(tilt) > 0.5f) {
                        renderer.elevation =
                            (renderer.elevation + tilt * 0.15f).coerceIn(3f, 87f)
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
        val moveX = -dx * metresPerPixel * rightX - dy * metresPerPixel * awayX
        val moveZ = -dx * metresPerPixel * rightZ - dy * metresPerPixel * awayZ
        renderer.target = floatArrayOf(t[0] + moveX, t[1], t[2] + moveZ)
        // Dragging while following does not end the chase, it leans out of it:
        // the camera keeps up with the model from where it has been put, so a
        // look at the ground beside it is not paid for by losing it. The follow
        // button comes back to the middle.
        if (following) {
            panX += moveX
            panZ += moveZ
        }
    }

    /** How far the camera has been leaned out of the chase, in metres. */
    private var panX = 0f
    private var panZ = 0f

    /**
     * Riding behind the model: the camera is turned to whichever way it is
     * pointing, so the view is the one from over its shoulder.
     */
    private var chasing = false

    /** Leaning out of that, in degrees, the way [panX] leans out of following. */
    private var chaseYaw = 0f

    /** The heading last drawn: from the model when it says, from its course when not. */
    private var lastModelHeading = 0f

    fun setChasing(on: Boolean) {
        chasing = on
        if (!on) {
            renderer.chasingModel = false
            renderer.azimuthWanted = Float.NaN
            return
        }
        chaseYaw = 0f
        // over its shoulder means keeping up with it, and from a low angle
        following = true
        panX = 0f
        panZ = 0f
        renderer.elevation = 22f
        renderer.distance = renderer.distance.coerceIn(80f, 400f)
        LiveFlightPath.latest()?.let { lookAt(it.lat, it.lon, it.altitudeMsl) }
        applyChaseBearing()
    }

    fun isChasing(): Boolean = chasing

    /**
     * Behind the model, looking the way it is going.
     *
     * The camera sits opposite its heading, so the aircraft is between it and
     * where it is headed. Ridden all the way to the attitude, which arrives far
     * more often than a position does, so a turn is smooth rather than stepped.
     */
    private fun applyChaseBearing() {
        // The renderer works out where behind the model is, from the heading it
        // is drawing the model at, and eases the camera round to it. Told the
        // heading from here instead, the camera followed the reported one while
        // the model followed an eased one, and the two turned apart.
        renderer.chaseYaw = chaseYaw
        renderer.chasingModel = chasing
    }

    /** Only a button does this now: no gesture gives up following. */
    private fun followingOff() {
        if (!following) return
        following = false
        chasing = false
        renderer.chasingModel = false
        renderer.azimuthWanted = Float.NaN
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
                smooth(gravity, event.values, hasGravity)
                hasGravity = true
            }
            android.hardware.Sensor.TYPE_MAGNETIC_FIELD -> {
                smooth(geomagnetic, event.values, hasGeomagnetic)
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

    /** Filtering the readings themselves, not the angle they produce. */
    private fun smooth(target: FloatArray, values: FloatArray, initialised: Boolean) {
        if (!initialised) {
            System.arraycopy(values, 0, target, 0, 3)
            return
        }
        for (i in 0..2) target[i] += (values[i] - target[i]) * 0.10f
    }

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
        // once, however many times this is called: start() posts it too, and
        // two chains of it ran the whole first session at twice the rate
        ticker.removeCallbacks(poll)
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
