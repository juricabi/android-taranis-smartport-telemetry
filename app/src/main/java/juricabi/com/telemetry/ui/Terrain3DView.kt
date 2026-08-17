package juricabi.com.telemetry.ui

import android.content.Context
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import juricabi.com.telemetry.gl.LiveFlightPath
import juricabi.com.telemetry.gl.TerrainRenderer
import juricabi.com.telemetry.gl.TerrainPager
import juricabi.com.telemetry.gl.TerrainScene
import juricabi.com.telemetry.utils.Elevation
import juricabi.com.telemetry.utils.Imagery

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

    /** The screen's loading ring, fed the pager's count on the UI thread. */
    @Volatile var onLoadingProgress: ((done: Int, total: Int) -> Unit)? = null
    @Volatile private var progressDone = -1
    @Volatile private var progressTotal = -1

    /**
     * Say the current count again — a settled world stops volunteering it,
     * so a settings return or an adopt would otherwise wait for the next
     * change to apply the switch.
     */
    fun republishProgress() {
        if (progressTotal >= 0) onLoadingProgress?.invoke(progressDone, progressTotal)
    }
    private val pager = TerrainPager(scene, renderer,
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .let { am -> android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) } }
            .totalMem)
    private val status = TextView(context)

    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastFocusY = 0f
    private var lastAngle = 0f
    private var seenVersion = -1
    @Volatile private var released = false

    /** The ground is up: until it is, there is nothing to draw anything on. */
    private var terrainReady = false

    /** Whether there is ground with a photograph on it to watch a flight over. */
    fun groundReady(): Boolean = terrainReady

    /**
     * Whether the ground has begun loading at all. A replay-bound view waits
     * for its flight before it builds anything, and holding playback for a
     * ground that is itself waiting for playback's first fix held both
     * forever.
     */
    fun groundBegun(): Boolean = started

    /**
     * Begin the ground at the flight's own first place, read from the log
     * before any of it is played — so a held replay starts over a finished
     * world instead of the world finishing around a started replay.
     */
    /**
     * A new flight, same world: the scene re-opens its per-flight
     * questions and the view forgets what belonged to the old flight —
     * the shown track, the claimed heading, the chase pose. The ground
     * stands; a far flight re-anchors it by its own road.
     */
    fun beginNewFlight(replay: Boolean) {
        if (released) return
        scene.beginNewFlight()
        groundFollowsPhone = !replay
        flightShown = false
        seenVersion = -1
        lastAppendedPoint = null
        // the rest of how far along the old flight this view had got: left
        // set, the new one appended from a stale index and arrived without
        // the snap that drops the camera onto it
        appendedThrough = 0
        placedOnce = false
        framedFlight = false
        hasAttitude = false
        modelHeadingKnown = false
        chasePoseApplied = false
        // Chase stays armed — the button is the person's, not the flight's —
        // but it lets go of the model that has gone. Both halves, as
        // setChasing does: the renderer writes the wanted bearing only while
        // chasing and then eases towards whatever it last held forever, so
        // clearing the steer alone left the camera springing back to a dead
        // flight's heading against every attempt to turn it.
        applyChaseBearing()
        renderer.azimuthWanted = Float.NaN
        pickUpNewPoints()
    }

    fun beginAt(lat: Double, lon: Double) {
        if (released || started || lat.isNaN() || lon.isNaN()) return
        start(emptyList(), lat, lon, myLat, myLon, myAccuracy)
    }

    /** Called once, when there is. A replay waits on this before it runs. */
    var onGroundReady: (() -> Unit)? = null

    /**
     * Locate was asked for ground this world does not reach. The screen
     * answers true when it has taken the question over — with nothing
     * connected, the world belongs to nobody and it offers to build one
     * here. A flight or a replay still owns it, and then it answers false
     * and the words below stand.
     */
    var onLocateBeyondWorld: (() -> Boolean)? = null

    /**
     * Whether a flight belongs on screen. Set when one is handed in at the
     * start, and when a fix arrives afterwards — so a finished flight left in
     * memory is not brought back the moment the ground finishes loading.
     */
    private var flightShown = false

    /**
     * A plan laid over the ground, remembering which points have found it.
     *
     * The ground arrives in pieces — the window loads around the model, and a
     * plan can reach well past it — so points still standing at the datum are
     * settled as ground turns up beneath them. A point already settled is
     * never read again: the tile it was read from may have left the small
     * shared height memory since, and reading it again would drop the point
     * back to the datum while the ground it settled onto is still on screen.
     */
    private class DrapedPlan(
        val lats: DoubleArray,
        val lons: DoubleArray,
        val points: FloatArray,
        val color: FloatArray
    ) {
        var set = TerrainRenderer.LineSet(
            points.copyOf(), color[0], color[1], color[2], color[3], true, 4f, true)
    }

    /** Plans already laid on the ground, by the plan they were laid from. */
    private val drapedPlans =
        HashMap<Pair<List<juricabi.com.telemetry.maps.Position>, Int>, DrapedPlan>()

    /**
     * Lay the plan onto the ground the eye actually sees, and re-lay it as
     * that ground refines — the way the rings do. Its height comes from the
     * drawn mesh (drawnGroundAt), not the data (groundAt): a plan settled once
     * on a coarse mesh sat buried the moment a finer one was drawn over it at
     * a close zoom. drawnGroundAt is null only where nothing is drawn yet, so
     * the data height and then the height the point already had are the
     * fallbacks — a point never drops to the datum under a passing gap.
     */
    private fun drapePlan(draped: DrapedPlan) {
        var changed = false
        for (i in draped.lats.indices) {
            val h = pager.drawnGroundAt(draped.lats[i], draped.lons[i])
                ?: scene.groundAt(draped.lats[i], draped.lons[i]) ?: continue
            val y = h - scene.originAltitude
            if (draped.points[i * 3 + 1] != y) {
                draped.points[i * 3 + 1] = y
                changed = true
            }
        }
        // a copy, because the renderer reads the one it holds on its own thread
        if (changed) {
            draped.set = TerrainRenderer.LineSet(
                draped.points.copyOf(), draped.color[0], draped.color[1],
                draped.color[2], draped.color[3], true, 4f, true)
        }
    }
    private var started = false

    private var myLat = Double.NaN
    private var myLon = Double.NaN
    private var myAccuracy = 0f

    /** The last ground height known under this phone, for a step past the edge. */
    private var myGround = Float.NaN

    /** What each ring was last built from, so a compass sample rebuilds neither. */
    private val myRingKey = longArrayOf(0L)
    private val loggedRingKey = longArrayOf(0L)

    /** Bumped when the ground under the rings changes, forcing a re-drape. */
    private var ringEpoch = 0

    /** The pager cover stamp the plans were last re-draped against. */
    private var lastPlanStamp = -1

    /** The same four things again, for the arrow that says where it was. */
    private var loggedLat = Double.NaN
    private var loggedLon = Double.NaN
    private var loggedAccuracy = 0f
    private var loggedHeading = 0f
    private var hasLoggedHeading = false
    private var loggedGround = Float.NaN
    private var myHeading = 0f

    /**
     * Whether the camera rides the model. No mode to pick: with nothing
     * arriving it frames what was flown, and the moment a point comes in it
     * follows. A touch takes control, a double tap gives it back.
     */
    private var following = true

    private val ticker = Handler()

    /**
     * Whether the tick is already going round.
     *
     * It starts the view when the first fix arrives, and starting the view
     * posts the tick — from inside the tick itself, where nothing can take back
     * the message already running. Two chains then ran for the life of the
     * view, rebuilding and re-uploading the whole flight twice as often as
     * anything needed.
     */
    private var polling = false

    private val poll = object : Runnable {
        override fun run() {
            if (released) return
            // Opened with nowhere to stand — no fix from the model, none from
            // the phone. Rather than stay black until the map type is changed
            // and back, start as soon as either turns up. A flight only counts
            // if it is live: one left in memory from earlier is not a reason.
            if (!started) {
                val live = if (flightShown) LiveFlightPath.latest() else null
                when {
                    live != null -> start(LiveFlightPath.snapshot(), live.lat, live.lon,
                        myLat, myLon, myAccuracy)
                    // the same guard as start's: a replay's ground belongs to
                    // its flight, not to the sofa the phone is on
                    groundFollowsPhone && !myLat.isNaN() && !myLon.isNaN() ->
                        start(emptyList(), myLat, myLon, myLat, myLon, myAccuracy)
                }
            }
            pickUpNewPoints()
            // a chase armed over an empty sky takes its pose the moment
            // there is a model to stand behind
            if (chasing && !chasePoseApplied) applyChasePose()
            // The ground under the arrows refines tile by tile; they
            // re-stand on whatever is drawn there now. A settled world
            // pays two height lookups — the rings rebuild only when the
            // ground in their key actually moves.
            placeMyArrow()
            placeLoggedArrow()
            // the plans re-drape onto the drawn surface as it refines, the
            // way the rings do — a plan settled on a coarse mesh sat buried
            // once a finer one drew over it. Only when the drawn ground has
            // actually moved, and only when there is a plan to re-lay.
            if (flightPlans.isNotEmpty() && pager.coverStamp != lastPlanStamp) {
                lastPlanStamp = pager.coverStamp
                rebuildOverlays()
            }
            if (!released) ticker.postDelayed(this, FOLLOW_INTERVAL_MS)
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

        // A context that did not survive lost its pictures — their heap copies
        // go the moment they are first uploaded. Fetch them back from disk.
        renderer.onPicturesLost = {
            postFromTerrain { pager.redress() }
        }
    }

    /**
     * Build the scene. [fallbackLat]/[fallbackLon] are used when nothing has
     * been flown yet, because the ground is worth looking at either way.
     */
    fun start(points: List<TerrainScene.TrackPoint>,
              fallbackLat: Double, fallbackLon: Double,
              myLatitude: Double, myLongitude: Double, accuracy: Float) {
        if (released) return
        // A world already up is being handed a flight, not being built. It
        // hears no fixes while it is parked behind the map, so a flight that
        // began — or ran to its end — during that time would otherwise wait
        // for the next one, which a finished replay never sends.
        if (started) {
            myLat = myLatitude
            myLon = myLongitude
            myAccuracy = accuracy
            if (points.isNotEmpty()) {
                flightShown = true
                // which lays the track out, re-anchors it if it is far, and
                // stands the model at its end — the tick's own road. One point
                // is a replay paused at its first fix: no track to lay, but a
                // model to stand, which pickUpNewPoints now does from a lone one.
                pickUpNewPoints()
            }
            return
        }
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
            // One point is not a track to lay out, but a replay paused at its
            // very first fix still has a place to stand the world on and a model
            // to show. Build on it and mark the flight shown, so switching into
            // 3D over a paused start draws the model once the ground is up —
            // rather than the empty screen it fell to when a replay, whose
            // fallback origin is deliberately none, dropped through to nothing.
            val lone = points.lastOrNull()
            if (lone != null) {
                scene.setOrigin(lone.lat, lone.lon)
                flightShown = true
            } else if (fallbackLat.isNaN() || fallbackLon.isNaN()) {
                if (groundFollowsPhone) status.text = "No position yet"
                // Not started, so nothing is loading and nothing will draw. The
                // tick still runs: the first fix to arrive starts the ground.
                started = false
                watch()
                return
            } else {
                scene.setOrigin(fallbackLat, fallbackLon)
            }
        }

        renderer.groundUnderCamera = { x, z ->
            val lat = scene.latAt(z)
            val lon = scene.lonAt(x)
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
        renderer.maxDistance = reachOfFlight()
        // no notice that it is loading: the empty screen says so already

        // The pager owns which tiles exist from here on. These are the three
        // moments the view has always cared about, wherever they come from.
        pager.onFirstDressed = {
            postFromTerrain { groundDressed() }
        }
        pager.onWorldMoved = {
            // Everything laid against the old world's heights: the plans,
            // the rings — and the flight itself. The tiles are rebuilt on
            // the new datum, and a track left un-rebuilt sat sunk into or
            // floating over them by the whole datum delta, with a paused
            // replay never triggering the rebuild on its own.
            postFromTerrain {
                drapedPlans.clear()
                ringEpoch++
                seenVersion = -1
                pickUpNewPoints()
                placeMyArrow()
                placeLoggedArrow()
            }
        }
        pager.onStatus = { message: String ->
            postFromTerrain {
                status.text = message
                // Nothing will ever build here — the sea, or no signal and no
                // cache. That is as ready as the ground will be: held any
                // longer, a replay over it would never play at all.
                if (message.isNotEmpty()) groundDressed()
            }
        }
        pager.onProgress = { done, total ->
            if (done != progressDone || total != progressTotal) {
                progressDone = done
                progressTotal = total
                postFromTerrain { onLoadingProgress?.invoke(done, total) }
            }
        }
        pager.start()
        watch()
    }

    /**
     * The view's two loops: the tick that follows the flight, and the watch
     * that reads the camera's bearing off it.
     *
     * Once each, however often this is called — start posts them and so does
     * coming back to the screen, and two chains of either run at twice the
     * rate for the life of the view.
     */
    private fun watch() {
        if (released) return
        if (!polling) {
            polling = true
            ticker.removeCallbacks(poll)
            ticker.post(poll)
        }
        removeCallbacks(bearingWatch)
        postOnAnimation(bearingWatch)
    }

    /**
     * The first tile stands with its picture on.
     *
     * The flight waits for that moment: the shape of the ground shows as it
     * comes, but a model flying across bare grey mesh with its whole path
     * drawn over it is a worse thing to watch than a moment of empty screen.
     * Everything that was waiting for ground to stand on goes now — the
     * flight, the camera snap, the overlays, and whoever held a replay.
     */
    private fun groundDressed() {
        if (terrainReady) return
        terrainReady = true
        ringEpoch++
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
        placeMyArrow()
        placeLoggedArrow()
        onGroundReady?.invoke()
    }

    /**
     * How far back the camera may be pulled: the whole region, always.
     *
     * This used to stop a couple of kilometres out, because the ground was a
     * window that far across and past it there was only sky. The ground is a
     * pyramid now — coarse tiles carry to the horizon — so pulling out to see
     * the flight against its mountains and coastline is the point, not a way
     * off the edge of the world.
     */
    private fun reachOfFlight(): Float = Math.max(150_000f, scene.extent * 6f)

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

    /** Which shape of the path those counts belong to. */
    private var seenGeneration = 0

    /** The one before it, to tell which way the model is going without attitude. */
    private var lastAppendedPoint: TerrainScene.TrackPoint? = null

    /** Whether the model has been put somewhere yet, for the camera to arrive at. */
    private var placedOnce = false

    /**
     * Whether this flight has been framed on its first appearance yet. Unlike
     * [placedOnce] it does NOT reset on a rewind: a backward seek forgets the
     * flight to redraw it, and keying the on-appear frame to placedOnce would
     * yank a free camera back to the model on every leftward drag of the
     * seekbar. Reset only when a genuinely new flight begins.
     */
    private var framedFlight = false

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

        // The path has been thinned under us, so every index kept here means a
        // different point now: start again rather than append from the middle
        // of somewhere else.
        if (LiveFlightPath.generation != seenGeneration) {
            seenGeneration = LiveFlightPath.generation
            appendedThrough = 0
            seenVersion = -1
        }

        // Nothing to grow from: the flight has been thrown away and is on its
        // way back, which is what a replay jumping somewhere else looks like.
        // Growing an empty flight does nothing — the renderer has no last point
        // to carry a curtain and a shadow on from — so the whole of it is built
        // here instead of on the tick. Left to the tick there was up to half a
        // second with the model in its new place and no flight drawn behind it,
        // and jumping between two paused positions made that the whole picture
        // until something else happened to move.
        // One point is no flight to build, and falls through: it is appended to
        // nothing, and the model is placed at the end of it as always — which
        // is what a replay opened at its beginning has, and all it needs.
        if (appendedThrough == 0 && LiveFlightPath.size() > 1) {
            // Placing the model and arriving at it, both of them, are the
            // rebuild's own work — the camera snaps to the first place the
            // model is put, and a flight thrown away has none.
            pickUpNewPoints()
            return
        }
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
                lastAppendedPoint?.let {
                    lastModelHeading = courseBetween(it, point)
                    modelHeadingKnown = true
                }
            }
            lastAppendedPoint = point
            renderer.appendFlightPoint(
                scene.east(point.lon),
                scene.heightOf(point),
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
        if (hasAttitude) {
            lastModelHeading = modelHeading
            modelHeadingKnown = true
        }
        applyChaseBearing()
        val x = scene.east(last.lon)
        val y = scene.heightOf(last)
        val z = -scene.north(last.lat)
        renderer.setModel(x, y, z, lastModelHeading,
            Math.max(15f, scene.extent / 40f), modelPitch, modelRoll)
        if (following) {
            renderer.target = floatArrayOf(x + panX, y, z + panZ)
            framedFlight = true
        } else if (!framedFlight) {
            // Frame the flight when it first appears even with no mode
            // selected, the way the 2D map recenters on the first fix — so the
            // view always arrives at the flight, live or replay, and only then
            // hands over to free control. Gated on framedFlight, not
            // placedOnce: a rewind forgets the flight to redraw it (resetting
            // placedOnce), and keying this to that would yank a free camera
            // back to the model on every leftward seek. framedFlight resets
            // only when a new flight begins, so a rewind leaves the camera put.
            renderer.target = floatArrayOf(x, y, z)
            framedFlight = true
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
        val fresh = version != seenVersion
        // A flight that has stopped still has its height question open, and
        // the answer wants ground that may not have loaded when it was first
        // asked. The question used to ride in on the next batch of points —
        // and a link that has dropped sends none, so an above-launch flight
        // opened afterwards stayed drawn under the hill it had flown over.
        if (!fresh && !scene.altitudeWorthAsking()) return
        val points = LiveFlightPath.snapshot()
        if (points.size < 2) {
            seenVersion = version
            // One point is a replay opened — or left paused — at its very first
            // fix: no line to draw from it, but the model belongs on the ground
            // the moment the ground is up, not held back until playback moves off
            // the start. This is the only road taken when ground finishes after
            // the flight has stopped arriving, so without it a replay stopped at
            // its start (autostart off) stood over bare terrain with nothing on
            // it. Just the model: the line and curtain need two points to join,
            // and leaving appendedThrough at 0 lets onNewPoint rebuild the whole
            // flight the instant the second point lands, not a tick later.
            if (points.isNotEmpty()) placeModel()
            return
        }

        // Before the track is built from them: this settles what their heights
        // mean, and the track is laid out in that answer.
        val frameMoved = scene.resolveAltitudeIfNeeded(points)
        // nothing new to draw and the answer did not move: leave the flight
        // exactly as it stands rather than rebuilding it every tick
        if (!fresh && !frameMoved) return
        seenVersion = version
        scene.buildTrack(points)
        renderer.setTrack(scene.track, scene.shadow)
        // as the flight grows, so does how far back the camera may be pulled
        renderer.maxDistance = reachOfFlight()
        // rebuilt from the whole flight, so everything is accounted for again
        appendedThrough = points.size

        val last = points[points.size - 1]
        val before = points[Math.max(0, points.size - 4)]
        // Where the nose is pointing when the model says so, since that differs
        // from the course over the ground in any wind; the course is the
        // fallback for links that carry no attitude.
        if (!hasAttitude) {
            lastModelHeading = courseBetween(before, last)
            modelHeadingKnown = true
        }
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
        // the pager reads the camera itself; this just spares it the wait
        pager.poke()
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
        myShown = true
        myLat = lat
        myLon = lon
        myAccuracy = accuracy
        placeMyArrow()
        // and the line drawn to it: it was rebuilt only when the model moved,
        // so with a replay paused or a link dropped it stayed pinned where the
        // phone had been while the arrow walked away from the end of it
        rebuildOverlays()
        if (!groundFollowsPhone) return
        // the camera follows the arrow, and the pager follows the camera —
        // a walk to the far side of the field just needs a nudge to look
        if (started && terrainReady && LiveFlightPath.size() < 2) {
            pager.poke()
        }
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

    /**
     * The heading in the corner, kept up with the camera.
     *
     * While chasing, the camera turns itself — nobody tells it to — so the only
     * way to report where it is pointing is to look. That was done on the same
     * half-second tick as everything else, which live is a camera drifting a
     * degree or two behind the model and unnoticeable. A replay turns it as
     * fast as the flight is being replayed, and twice a second then reads as a
     * number that jumps while the map beside it turns smoothly.
     *
     * Once per frame, on the screen's own clock, which is what the map's
     * heading readout runs on — a timer of its own turned the same swing into
     * coarser steps than the map made of it, and the two views disagreed about
     * how smoothly the same number moved. Reported only when it has really
     * moved: the reading is whole degrees, so anything finer would be spent
     * redrawing the same text.
     */
    private var reportedBearing = Float.NaN

    private val bearingWatch = object : Runnable {
        override fun run() {
            if (chasing) {
                val now = renderer.azimuth
                val moved = Math.abs(((now - reportedBearing + 540f) % 360f) - 180f)
                if (reportedBearing.isNaN() || moved > 0.5f) {
                    reportedBearing = now
                    onBearingChanged?.invoke(now)
                }
            }
            // a released view must not keep a Choreographer callback alive
            if (!released) postOnAnimation(this)
        }
    }

    /** Where the camera is pointing now, for whoever has just started listening. */
    fun bearing(): Float = renderer.azimuth

    /** Put the camera on a place, without following anything. */
    fun lookAt(lat: Double, lon: Double, altitudeMsl: Float?) {
        val ground = scene.groundAt(lat, lon)
        val height = when {
            // through the same reference as the model, or following it aimed
            // the camera at a point buried under the hill it is flying over
            altitudeMsl != null && !altitudeMsl.isNaN() ->
                scene.aboveSeaLevel(altitudeMsl) - scene.originAltitude
            ground != null -> ground - scene.originAltitude
            else -> 0f
        }
        renderer.target = floatArrayOf(scene.east(lon), height, -scene.north(lat))
    }

    /**
     * Whether a place stands on ground this world can ever load. The roots
     * ring the origin as far as the pager reaches; past them there is only
     * the void, and no control should take the camera there.
     */
    private fun inWorld(lat: Double, lon: Double): Boolean {
        if (lat.isNaN() || lon.isNaN()) return false
        return Math.abs(scene.east(lon)) < TerrainPager.ROOT_REACH_M &&
            Math.abs(scene.north(lat)) < TerrainPager.ROOT_REACH_M
    }

    /**
     * Whether a place stands close enough to the world's origin to keep
     * the world for it — the same fifty kilometres past which a flight
     * re-anchors, asked from outside before choosing to.
     */
    fun worldNear(lat: Double, lon: Double): Boolean {
        if (released || lat.isNaN() || lon.isNaN()) return false
        return Math.hypot(scene.east(lon).toDouble(), scene.north(lat).toDouble()) <=
            TerrainScene.KEEP_WORLD_WITHIN_M
    }

    fun goToMyLocation(): Boolean {
        if (myLat.isNaN() || myLon.isNaN()) return false
        // Replaying a flight from another country, the phone stands outside
        // the flight's whole world — flying the camera there showed the
        // dark past the world's edge, with no word why. A toast, not the
        // status line: the replay writes its flight mode there every tick,
        // and the explanation was gone before it could be read.
        if (!inWorld(myLat, myLon)) {
            if (onLocateBeyondWorld?.invoke() == true) return true
            android.widget.Toast.makeText(context,
                "You are beyond this flight's ground",
                android.widget.Toast.LENGTH_SHORT).show()
            return true
        }
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

    private var flightPlans: List<Pair<List<juricabi.com.telemetry.maps.Position>, Int>> =
        emptyList()
    private var traffic: List<juricabi.com.telemetry.manager.Fr24Manager.AirplaneInfo> = emptyList()
    /** Over a replay: the home line anchors to the record or not at all. */
    @Volatile var homeFromRecorded = false

    private var homeLineOn = false
    private var headingLineOn = false
    private var homeLineColor = 0
    private var headingLineColor = 0

    /** Playback only: to where the operator stood, as recorded. */
    private var operatorLineOn = false
    private var operatorLineColor = 0


    fun setOverlaySettings(homeLine: Boolean, homeColor: Int,
                           headingLine: Boolean, headingColor: Int,
                           operatorLine: Boolean, operatorColor: Int) {
        homeLineOn = homeLine
        homeLineColor = homeColor
        headingLineOn = headingLine
        headingLineColor = headingColor
        operatorLineOn = operatorLine
        operatorLineColor = operatorColor
        rebuildOverlays()
    }

    fun setFlightPlans(plans: List<Pair<List<juricabi.com.telemetry.maps.Position>, Int>>) {
        flightPlans = plans
        rebuildOverlays()
    }

    /** Real aircraft, at the altitude they are actually flying at. */
    fun setTraffic(airplanes: List<juricabi.com.telemetry.manager.Fr24Manager.AirplaneInfo>) {
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

        // The home line: to where this phone is now, live and playback
        // alike — walking to a downed model is its whole use. It stands on
        // the arrow's own position, so hiding the arrow takes the line with
        // it: a line pointing at nothing points at nothing.
        //
        // Both lines start at the model, so the renderer draws them from
        // where it is drawing the model. Given as vertices here they jumped
        // to each fix and then waited, while the model glided between them.
        val homeLat = myLat
        val homeLon = myLon
        val home = if (homeLat.isNaN() || homeLon.isNaN()) {
            null
        } else {
            // Standing outside the loaded window but inside the world, the
            // datum plane anchors the line rather than hiding it.
            scene.groundAt(homeLat, homeLon) ?: scene.originAltitude
        }
        // myShown too: this line hides with the arrow — switched off, or
        // beyond the world's edge where the arrow no longer stands. A blue
        // line to a country the world does not contain ran to the horizon.
        if (homeLineOn && myShown && model != null && home != null &&
            inWorld(homeLat, homeLon)) {
            val c = colorOf(homeLineColor)
            renderer.setHomeLine(true, scene.east(homeLon), home - scene.originAltitude,
                -scene.north(homeLat), c[0], c[1], c[2], c[3])
        } else {
            renderer.setHomeLine(false, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        // The operator position line, playback only: to where the operator
        // stood as recorded — orange, like the arrow it points at. With no
        // record there is no line; drawn to anywhere else it ran off across
        // the county the flight was not recorded in.
        val operatorGround = if (operatorLineOn && homeFromRecorded && model != null &&
            !loggedLat.isNaN() && !loggedLon.isNaN()) {
            scene.groundAt(loggedLat, loggedLon)
        } else null
        if (operatorGround != null) {
            val c = colorOf(operatorLineColor)
            renderer.setOperatorLine(true, scene.east(loggedLon),
                operatorGround - scene.originAltitude, -scene.north(loggedLat),
                c[0], c[1], c[2], c[3])
        } else {
            renderer.setOperatorLine(false, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        // The last stretch, from where the track was last built to where the
        // model is now.
        //
        // The track itself is rebuilt twice a second — it is the whole path, and
        // doing that for every fix is what left no room for anything else — but
        // the model moves on every one of them, so its line was trailing half a
        // second behind it. Two vertices close the gap.
        // where it is heading, a kilometre of it
        // Not only where an attitude is reported: without one the model is
        // pointed along its course over the ground, which is what the map has
        // always drawn its own heading line from.
        if (headingLineOn && model != null) {
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
            val already = drapedPlans[entry]
            if (already != null) {
                drapePlan(already)
                sets.add(already.set)
                continue
            }

            val lats = ArrayList<Double>(plan.size * 8)
            val lons = ArrayList<Double>(plan.size * 8)
            lats.add(plan[0].lat)
            lons.add(plan[0].lon)
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
                    lats.add(from.lat + (to.lat - from.lat) * part)
                    lons.add(from.lon + (to.lon - from.lon) * part)
                }
            }

            val count = lats.size
            val points = FloatArray(count * 3)
            for (i in 0 until count) {
                points[i * 3] = scene.east(lons[i])
                // at the datum until the ground under it arrives
                points[i * 3 + 1] = 0f
                points[i * 3 + 2] = -scene.north(lats[i])
            }
            val draped = DrapedPlan(
                DoubleArray(count) { lats[it] },
                DoubleArray(count) { lons[it] },
                points,
                colorOf(entry.second)
            )
            drapePlan(draped)
            drapedPlans[entry] = draped
            sets.add(draped.set)
        }

        // Traffic, at the height it is actually flying: a post from the ground
        // up to the aircraft — the thing a flat map cannot show you — with a
        // small plane on top of it, pointed down its track, in place of the
        // cross that used to say only "something, somewhere up here".
        if (traffic.isNotEmpty()) {
            val posts = FloatArray(traffic.size * 6)
            val planes = FloatArray(traffic.size * 4)
            var p = 0
            var q = 0
            for (plane in traffic) {
                val lat = plane.lat.toDouble()
                val lon = plane.lon.toDouble()
                val ground = scene.groundAt(lat, lon) ?: scene.originAltitude
                val x = scene.east(lon)
                val z = -scene.north(lat)
                // The reported height is barometric — the feed carries no
                // other — and near high ground it can sit a shade under a
                // ridge the aircraft really cleared. The true height is not
                // knowable from here, but the one visible wrong is: nothing
                // is drawn inside a hill.
                val top = Math.max((plane.altMeters - scene.originAltitude).toFloat(),
                    ground - scene.originAltitude + 60f)
                posts[p++] = x; posts[p++] = ground - scene.originAltitude; posts[p++] = z
                posts[p++] = x; posts[p++] = top; posts[p++] = z
                planes[q++] = x; planes[q++] = top; planes[q++] = z
                planes[q++] = plane.track.toFloat()
            }
            sets.add(TerrainRenderer.LineSet(posts, 1f, 1f, 1f, 0.35f, false, 2f, false))
            renderer.setTraffic(planes)
            // the same aircraft, in the same order, for a tap to ask against
            trafficShown = traffic
            trafficPlaces = planes
        } else {
            renderer.setTraffic(FloatArray(0))
            trafficShown = emptyList()
            trafficPlaces = FloatArray(0)
        }

        renderer.setOverlays(sets)
    }

    /** What a tap can hit: the aircraft drawn, beside where each was drawn. */
    private var trafficShown: List<juricabi.com.telemetry.manager.Fr24Manager.AirplaneInfo> =
        emptyList()
    private var trafficPlaces = FloatArray(0)

    /** Told which aircraft a tap landed on, to say who it is. */
    var onTrafficTapped: ((juricabi.com.telemetry.manager.Fr24Manager.AirplaneInfo) -> Unit)? =
        null

    /**
     * The aircraft nearest the tap, within a finger of it on screen.
     *
     * Projected against the frame last drawn, which is what the finger was
     * aiming at; the world positions are the ones the planes were drawn from,
     * so the two cannot disagree about where an aircraft is.
     */
    private fun pickTraffic(tapX: Float, tapY: Float): Boolean {
        val shown = trafficShown
        val places = trafficPlaces
        if (shown.isEmpty() || places.size < shown.size * 4) return false
        val reach = 44f * resources.displayMetrics.density
        val screen = FloatArray(2)
        var bestAt = -1
        var bestDist = reach
        for (i in shown.indices) {
            val base = i * 4
            if (!renderer.projectToScreen(places[base], places[base + 1],
                    places[base + 2], screen)) continue
            val dx = screen[0] - tapX
            val dy = screen[1] - tapY
            val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (d < bestDist) {
                bestDist = d
                bestAt = i
            }
        }
        if (bestAt < 0) return false
        onTrafficTapped?.invoke(shown[bestAt])
        return true
    }

    /** The model's colour, from the same setting the map marker uses. */
    fun setModelColor(argb: Int) {
        val c = colorOf(argb)
        renderer.modelColor = floatArrayOf(c[0], c[1], c[2], 1f)
    }

    /** The route line colour, so the flight looks the same in both views. */
    /** The two position arrows, in the colours chosen in the settings. */
    fun setArrowColours(live: Int, logged: Int) {
        renderer.setArrowColours(live, logged)
        placeMyArrow()
        placeLoggedArrow()
    }

    fun setTrackColor(argb: Int) {
        val c = colorOf(argb)
        renderer.trackColor = floatArrayOf(c[0], c[1], c[2], 1f)
    }

    /** Quad, plane or heli, from the setting; the map marker follows it too. */
    fun setModelShape(shape: String) {
        if (renderer.modelShape == shape) return
        renderer.modelShape = shape
        // The new shape is built where the model is placed, and what places it
        // is a fix arriving — so a replay standing still went on showing the old
        // one until the flight moved again, while the map's marker changed at
        // once. Only where there is already a model standing somewhere: before
        // the ground is up there is deliberately nothing drawn.
        if (terrainReady && placedOnce) placeModel()
    }

    /** The model's own attitude, which is worth far more than its shape. */
    fun setModelAttitude(heading: Float, pitch: Float, roll: Float) {
        if (chasing) {
            lastModelHeading = heading
            modelHeadingKnown = true
            applyChaseBearing()
        }
        hasAttitude = true
        modelHeading = heading
        modelPitch = pitch
        modelRoll = roll
    }

    /** Nothing known about where this phone is: draw neither arrow nor ring. */
    fun hideMyLocation() {
        // The place is kept. Locate is about where to look, not about what is
        // drawn, and forgetting where the phone was left that button refusing
        // to work for as long as the arrow was switched off.
        myAccuracy = 0f
        myShown = false
        // said outright: handing it a position of nothing left it visible at
        // coordinates that are not numbers, which draws nothing only because
        // nothing is what a NaN triangle comes to
        renderer.hideMyLocation()
        renderer.setAccuracyCircle(FloatArray(0))
        // the ring was just cleared, so the key that said it was built must
        // go too, or re-showing the arrow at the same fix brings no ring back
        myRingKey[0] = 0L
    }

    /**
     * Where the phone stood while the flight being replayed was recorded.
     *
     * Drawn in orange beside the blue one, which is where it is now — the two
     * can be a county apart, and saying which is which by colour is the whole
     * point of drawing both.
     */
    fun setLoggedPosition(lat: Double, lon: Double, accuracy: Float, heading: Float) {
        loggedLat = lat
        loggedLon = lon
        loggedAccuracy = if (accuracy.isNaN()) 0f else accuracy
        hasLoggedHeading = !heading.isNaN()
        if (hasLoggedHeading) loggedHeading = heading
        placeLoggedArrow()
        // the line home is drawn to this one while a replay has it
        rebuildOverlays()
    }

    fun hideLoggedLocation() {
        loggedLat = Double.NaN
        loggedLon = Double.NaN
        loggedAccuracy = 0f
        hasLoggedHeading = false
        renderer.hideLoggedLocation()
        renderer.setLoggedCircle(FloatArray(0))
        loggedRingKey[0] = 0L
        // back to the live phone for the line home
        rebuildOverlays()
    }

    /** Whether this phone has said which way it is facing. */
    private var hasMyHeading = false

    fun setMyHeading(degrees: Float) {
        hasMyHeading = !degrees.isNaN()
        if (hasMyHeading) myHeading = degrees
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
    /** Whether the live arrow is wanted at all: the replay menu can say not. */
    private var myShown = true

    /**
     * Whether the ground should follow this phone when nothing is flying.
     *
     * It should while a link is up or expected — driving to the far side of a
     * field takes the arrow past the loaded ground otherwise. It should not
     * over a replay: the flight there was recorded somewhere else, and letting
     * the phone pull the window meant a replay left standing at its first
     * position quietly loaded the ground around the sofa and played the flight
     * over empty space.
     */
    var groundFollowsPhone = true

    private fun placeMyArrow() {
        // beyond the world's edge there is no ground to stand it on
        if (!myShown || !inWorld(myLat, myLon)) {
            renderer.hideMyLocation()
            renderer.setAccuracyCircle(FloatArray(0))
            return
        }
        myGround = standArrow(
            myLat, myLon, myAccuracy, myHeading, myGround, myRingKey,
            { x, y, z, heading -> renderer.setMyLocation(x, y, z, heading, hasMyHeading) },
            { ring -> renderer.setAccuracyCircle(ring) }
        )
    }

    private fun placeLoggedArrow() {
        loggedGround = standArrow(
            loggedLat, loggedLon, loggedAccuracy, loggedHeading, loggedGround, loggedRingKey,
            { x, y, z, heading ->
                renderer.setLoggedLocation(x, y, z, heading, hasLoggedHeading)
            },
            { ring -> renderer.setLoggedCircle(ring) }
        )
    }

    /**
     * Stand an arrow on the ground with its accuracy ring around it, and say
     * what height it ended up at.
     *
     * The two have to be built in the same breath: the ring used to be worked
     * out once, when the ground arrived, while the arrow was rebuilt on every
     * compass sample — so when the altitude reference settled and moved the
     * origin, the arrow followed and the ring was left hanging where it was.
     *
     * No ground is fetched for either of them. The ground is gathered around
     * the flight; the phone watching a replay of a flight from another field is
     * a place to draw, not a reason to load half a county. Where nothing is
     * known of the ground beneath it, the last height it stood at will do.
     */
    private fun standArrow(
        lat: Double, lon: Double, accuracy: Float, heading: Float, lastGround: Float,
        ringKey: LongArray,
        arrow: (Float, Float, Float, Float) -> Unit,
        circle: (FloatArray) -> Unit
    ): Float {
        if (!terrainReady || lat.isNaN() || lon.isNaN()) return lastGround
        // The ground the eye sees, not the ground as it will be. The true
        // z15 height under the phone is warmed early for the datum, but the
        // drawn surface may still be a coarse mesh bridging the whole
        // valley far above it — an arrow stood on truth was buried inside
        // the picture, a sliver of it poking through. Read from the drawn
        // mesh itself, it rides the surface down as detail arrives.
        val ground = pager.drawnGroundAt(lat, lon)
            ?: scene.groundAt(lat, lon) ?: lastGround
        if (ground.isNaN()) return lastGround
        arrow(
            scene.east(lon), ground - scene.originAltitude + 0.1f,
            -scene.north(lat), heading
        )

        // The ring does not turn when the phone turns. Rebuilding its
        // sixty-four draped points on every compass sample was thousands of
        // ground lookups a second on the screen's thread; it owes a rebuild
        // only to a new place, a new accuracy, or new ground under it —
        // which is why the ground is in the key: each refinement under the
        // arrow re-drapes the ring onto the surface it now stands on.
        val key = ((java.lang.Double.doubleToLongBits(lat) * 31 +
            java.lang.Double.doubleToLongBits(lon)) * 31 +
            accuracy.toRawBits() + ringEpoch) * 31 + ground.toRawBits()
        if (key == ringKey[0]) return ground
        ringKey[0] = key

        // an unknown accuracy is not a small one: drop the ring rather than
        // leave the last one it had lying there
        if (accuracy.isNaN() || accuracy <= 0f) {
            circle(FloatArray(0))
            return ground
        }
        val metresPerDegreeLon = scene.metresAcross(lat)
        val ring = FloatArray(CIRCLE_SEGMENTS * 3)
        var i = 0
        for (step in 0 until CIRCLE_SEGMENTS) {
            val angle = 2.0 * Math.PI * step / CIRCLE_SEGMENTS
            val pointLat = lat + accuracy * Math.cos(angle) / scene.metresUp()
            val pointLon = lon + accuracy * Math.sin(angle) / metresPerDegreeLon
            // the same drawn surface as the arrow, or the ring sinks
            val h = pager.drawnGroundAt(pointLat, pointLon)
                ?: scene.groundAt(pointLat, pointLon) ?: ground
            ring[i++] = scene.east(pointLon)
            ring[i++] = h - scene.originAltitude + 0.15f
            ring[i++] = -scene.north(pointLat)
        }
        circle(ring)
        return ground
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // The camera is about to move, and only gestures move it without a
        // fix arriving: without this nudge the pager slept out its idle
        // timers before noticing a zoom, and the wait read as slow loading.
        // Only at the gesture's edges — the pager keeps itself awake while
        // the camera moves, and poking per drag sample fought over its lock.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP -> pager.poke()
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                tapCandidate = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tapCandidate = false
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
                        val f = lastSpan / span
                        renderer.distance =
                            (renderer.distance * f).coerceIn(60f, renderer.maxDistance)
                        // Zooming in slides the focus DOWN THE VIEW RAY toward
                        // the ground — forward as much as down, the way the
                        // camera is looking — so a pinch moves toward what is
                        // ahead rather than dropping to the ground beneath the
                        // eye. The focus stays on the ray, so the view does not
                        // tilt, and the eye follows because it is worked out
                        // from the focus. Held off only while keeping up with a
                        // live model (following or chasing aims the focus at the
                        // plane); let go of it — chase or locate off, or the
                        // flight gone — and it is on. Only the pinch moves it.
                        if (f < 1f && (LiveFlightPath.latest() == null || !following)) {
                            val t = renderer.target
                            val g = renderer.groundUnderCamera?.invoke(t[0], t[2])
                            if (g != null && t[1] > g) {
                                val az = Math.toRadians(renderer.azimuth.toDouble())
                                val el = Math.toRadians(renderer.elevation.toDouble())
                                val uy = -Math.sin(el).toFloat()
                                if (uy < -0.05f) {
                                    // along the ray until this pinch's share of
                                    // the height above ground is shed
                                    val along = (t[1] - g) * (1f - f) / -uy
                                    val ux = -(Math.cos(el) * Math.sin(az)).toFloat()
                                    val uz = -(Math.cos(el) * Math.cos(az)).toFloat()
                                    renderer.target = floatArrayOf(
                                        t[0] + ux * along, t[1] + uy * along,
                                        t[2] + uz * along)
                                }
                            }
                        }
                    }
                    // a twist turns the world, the way it does on a map
                    var turn = angle - lastAngle
                    while (turn > 180f) turn -= 360f
                    while (turn < -180f) turn += 360f
                    if (Math.abs(turn) < 40f) {
                        // an armed chase with nothing to stand behind is not
                        // driving the camera, so it must not eat the twist
                        // either: the yaw offset went into an orbit nobody
                        // was flying, and rotation read as dead
                        if (chasing && chasePoseApplied) {
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
                    if (tapCandidate) {
                        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                        if (Math.abs(event.x - downX) > slop ||
                            Math.abs(event.y - downY) > slop) {
                            tapCandidate = false
                        }
                    }
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
            MotionEvent.ACTION_UP -> {
                // a finger that went down and came up where it went down is a
                // tap, and a tap is a question about what it landed on
                if (tapCandidate) pickTraffic(event.x, event.y)
                tapCandidate = false
            }
            MotionEvent.ACTION_CANCEL -> tapCandidate = false
        }
        return true
    }

    private var downX = 0f
    private var downY = 0f
    private var tapCandidate = false

    /** Drag the ground with the finger, scaled by how far out the camera is. */
    private fun panBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val height = Math.max(1, resources.displayMetrics.heightPixels)
        // Scaled by the distance to the GROUND under the focus, not to the
        // focus: while a pinch is still sinking the focus down from a chased
        // model the focus sits nearer than the ground, and a drag tuned to it
        // crawls until the zoom finishes. On the ground the two are the same.
        val ft = renderer.target
        val g = renderer.groundUnderCamera?.invoke(ft[0], ft[2])
        val reach = if (g == null) renderer.distance else {
            val ex = renderer.eyeX - ft[0]
            val ey = renderer.eyeY - g
            val ez = renderer.eyeZ - ft[2]
            Math.sqrt((ex * ex + ey * ey + ez * ez).toDouble()).toFloat()
        }
        val metresPerPixel = reach * 0.93f / height
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

    /** Chase turns nothing until a model heading — attitude or course — exists. */
    private var modelHeadingKnown = false

    /** Whether the shoulder pose has been taken for the current chase. */
    private var chasePoseApplied = false

    /** Leaning out of that, in degrees, the way [panX] leans out of following. */
    private var chaseYaw = 0f

    /** The heading last drawn: from the model when it says, from its course when not. */
    private var lastModelHeading = 0f

    fun setChasing(on: Boolean) {
        chasing = on
        if (!on) {
            renderer.chasingModel = false
            renderer.azimuthWanted = Float.NaN
            chasePoseApplied = false
            return
        }
        chaseYaw = 0f
        following = true
        // The shoulder pose only over an actual model: armed with an empty
        // sky, the low angle panned the camera down at nothing — the
        // bearing was guarded, the pose was not, and the guard's spirit
        // covers both. The tick applies it the moment a flight appears.
        chasePoseApplied = false
        if (LiveFlightPath.latest() != null) applyChasePose()
        applyChaseBearing()
    }

    /** The over-its-shoulder pose: keeping up with it, from a low angle. */
    private fun applyChasePose() {
        val latest = LiveFlightPath.latest() ?: return
        panX = 0f
        panZ = 0f
        renderer.elevation = 22f
        renderer.distance = renderer.distance.coerceIn(80f, 400f)
        lookAt(latest.lat, latest.lon, latest.altitudeMsl)
        chasePoseApplied = true
    }

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
        // And only once a heading exists: with nothing flying, the camera
        // swung round behind a phantom model facing its default north — the
        // same no-drone chase bug the map had, worn the 3D way.
        renderer.chaseYaw = chaseYaw
        renderer.chasingModel = chasing && modelHeadingKnown
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

    // The compass is read once, by the screen this view belongs to, and
    // handed to everything that draws with it — [setMyHeading] here, the map's
    // own arrow, and the recording. Three readers of the same two sensors is
    // three sets of samples and three lots of filtering for one number.

    fun onResume() {
        surface.onResume()
        pager.resume()
        watch()
    }

    fun onPause() {
        surface.onPause()
        // The GL thread stops with the surface, and it is the only thing
        // that drains what the pager produces — left running, the pager
        // filled an undrainable queue for as long as the app sat in the
        // background.
        pager.pause()
        ticker.removeCallbacks(poll)
        polling = false
        removeCallbacks(bearingWatch)
    }

    fun release() {
        if (released) return
        released = true
        ticker.removeCallbacks(poll)
        polling = false
        removeCallbacks(bearingWatch)
        // These closures belong to the Activity. A terrain worker may still be
        // returning from one blocking network request after abandon(), so do
        // not let the retired view keep or call back into its dead screen.
        onGroundReady = null
        onFollowingLost = null
        onBearingChanged = null
        onTrafficTapped = null
        onLoadingProgress = null
        onLocateBeyondWorld = null
        renderer.onPicturesLost = null
        pager.onFirstDressed = null
        pager.onWorldMoved = null
        pager.onStatus = null
        pager.onProgress = null
        pager.abandon()
        // and the ground: the thread loading it holds this view, and its
        // pictures are the largest thing the app ever has in its hands
        scene.abandon()
        // whatever was queued for a GL thread that is now gone
        renderer.shutdown()
    }

    /** A worker may finish after this view has been detached. */
    private fun postFromTerrain(action: () -> Unit) {
        if (released) return
        post { if (!released) action() }
    }
}
