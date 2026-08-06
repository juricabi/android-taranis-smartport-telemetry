package juricabi.com.telemetry.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the scene: ground, the aerial view draped over it, and the flight.
 *
 * The camera orbits a point — an angle round, an angle up, and a distance —
 * which is what a finger on a screen naturally controls, and what keeps the
 * flight in view however the ground is shaped.
 */
class TerrainRenderer : GLSurfaceView.Renderer {

    companion object {
        private const val TERRAIN_VERTEX = """
            uniform mat4 uMvp;
            attribute vec4 aPosition;
            attribute vec2 aTexture;
            attribute vec3 aNormal;
            varying vec2 vTexture;
            varying float vShade;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexture = aTexture;
                // a fixed light from the north west, the way a printed map is lit
                vec3 light = normalize(vec3(-0.5, 0.8, -0.4));
                // How much the face lies across the light, not which way it
                // faces it. A procedural solid built from boxes and posts does
                // not wind every triangle the same way, and a signed dot turns
                // whichever ones came out backwards flat black — this cannot,
                // while still telling a top from a side from an end.
                float lit = abs(dot(normalize(aNormal), light));
                vShade = 0.70 + 0.30 * lit;
            }
        """

        private const val TERRAIN_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uHasTexture;
            uniform vec3 uBase;
            varying vec2 vTexture;
            varying float vShade;
            void main() {
                vec3 base = mix(uBase, texture2D(uTexture, vTexture).rgb, uHasTexture);
                gl_FragColor = vec4(base * vShade, 1.0);
            }
        """

        /** The model: shaded, with its own edges drawn in the same pass. */
        private const val MODEL_VERTEX = """
            uniform mat4 uMvp;
            attribute vec4 aPosition;
            attribute vec3 aCorner;
            attribute vec3 aNormal;
            varying vec3 vCorner;
            varying float vShade;
            void main() {
                gl_Position = uMvp * aPosition;
                vCorner = aCorner;
                // How much a face lies across the light, not which way it faces
                // it. A solid built from boxes and posts does not wind every
                // triangle alike, and a signed dot leaves whichever came out
                // backwards flat black; this cannot, and still tells a top from
                // a side from an end.
                vec3 light = normalize(vec3(-0.5, 0.8, -0.4));
                vShade = 0.82 + 0.18 * abs(dot(normalize(aNormal), light));
            }
        """

        /**
         * Screen space edges: how fast a corner weight changes across a pixel
         * says how wide a line of even thickness has to be, whatever the size or
         * angle of the triangle. Without it a long thin face gets a hairline
         * down its side and a fat band across its end.
         */
        private const val MODEL_FRAGMENT_EVEN = """
            #extension GL_OES_standard_derivatives : enable
            precision mediump float;
            uniform vec3 uBase;
            uniform float uInk;
            varying vec3 vCorner;
            varying float vShade;
            void main() {
                vec3 wide = fwidth(vCorner) * uInk;
                vec3 near = smoothstep(vec3(0.0), wide, vCorner);
                float ink = min(min(near.x, near.y), near.z);
                vec3 colour = mix(vec3(0.13, 0.13, 0.16), uBase * vShade, ink);
                gl_FragColor = vec4(colour, 1.0);
            }
        """

        /** The same, for anything without derivatives: a line of even share. */
        private const val MODEL_FRAGMENT = """
            precision mediump float;
            uniform vec3 uBase;
            uniform float uInk;
            varying vec3 vCorner;
            varying float vShade;
            void main() {
                float edge = min(min(vCorner.x, vCorner.y), vCorner.z);
                float ink = smoothstep(0.0, uInk * 0.028, edge);
                vec3 colour = mix(vec3(0.13, 0.13, 0.16), uBase * vShade, ink);
                gl_FragColor = vec4(colour, 1.0);
            }
        """

        private const val LINE_VERTEX = """
            uniform mat4 uMvp;
            attribute vec4 aPosition;
            void main() {
                gl_Position = uMvp * aPosition;
                gl_PointSize = 8.0;
            }
        """

        private const val LINE_FRAGMENT = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """

        /** How much of the way to what it has been told, each frame. */
        private const val SMOOTHING = 0.18f

        /** How many fixes can arrive between two rebuilds of the flight. */
        private const val SPARE_POINTS = 600

        private const val FLOATS_PER_VERTEX = 8

        /** The model layout: position, corner weights, normal. */
        private const val MODEL_FLOATS = 9
    }

    /**
     * A tile living on the graphics card.
     *
     * Its geometry is uploaded once. Drawing from a FloatBuffer instead meant
     * handing the driver every vertex of every tile on every frame — a third of
     * a million vertices, ten megabytes, sixty times a second — which is what
     * made the ground feel heavy.
     */
    private class Tile(
        val key: Long,
        val vertexBuffer: Int,
        val indexBuffer: Int,
        val count: Int,
        var textureId: Int
    )

    private val tiles = ArrayList<Tile>()
    private val pending = ArrayList<TerrainScene.TileMesh>()

    /**
     * The meshes as handed in, kept so they can be uploaded again.
     *
     * Leaving the app throws away the GL context and everything in it; without
     * this the view came back black, because what had been uploaded was gone
     * and nothing was left to upload. Only the geometry comes back from here
     * now: the pictures are recycled as they reach the card, and a lost
     * context gets them again through [onPicturesLost] and the disk cache.
     */
    private val submitted = ArrayList<TerrainScene.TileMesh>()

    /** Told when a new context finds its pictures recycled; runs on the GL thread. */
    @Volatile var onPicturesLost: (() -> Unit)? = null

    /** The tiles still wanted, once the scene has said. Null means all of them. */
    private var keep: HashSet<Long>? = null
    private var keepChanged = false

    private var trackBuffer: FloatBuffer? = null
    private var trackCount = 0
    private var shadowBuffer: FloatBuffer? = null
    private var shadowCount = 0
    private var dropBuffer: FloatBuffer? = null

    /**
     * The flight is three things — a line in the air, its shadow on the ground
     * and the curtain hanging between them — and they have to agree about where
     * it ends, or the joins between them tear.
     *
     * All three are rebuilt together twice a second and all three grow together
     * on every fix in between, so the last point of each is the newest fix. The
     * model is drawn a little behind that, easing towards it, so it is always
     * somewhere along that final stretch — and each of the three is drawn up to
     * the one before it and then on to the model. Nothing to decide per frame,
     * and nothing that can fall short, overshoot or disagree.
     */
    private var headGroundY = 0f
    private val headQuad = FloatArray(18)
    private val headQuadBuffer = floats(FloatArray(18))

    private val leaderPair = FloatArray(6)
    private val leaderPairBuffer = floats(FloatArray(6))

    @Volatile private var homeOn = false
    private var homeX = 0f
    private var homeY = 0f
    private var homeZ = 0f
    private var homeR = 1f
    private var homeG = 1f
    private var homeB = 1f
    private var homeA = 1f

    @Volatile private var headingOn = false
    private var headR = 1f
    private var headG = 1f
    private var headB = 1f
    private var headA = 1f
    private var dropCount = 0

    /** How many points of flight each built quad of curtain spans. */
    private var curtainStep = 1

    /** And how many quads have been added a point at a time since. */
    private var appendedQuads = 0

    private var terrainProgram = 0
    private var modelProgram = 0
    private var lineProgram = 0

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val liftMatrix = FloatArray(16)
    private val liftMvp = FloatArray(16)

    /** How much the camera is being held up off the ground, eased over frames. */
    private var floorLift = 0f

    /**
     * Where the camera and the model actually are this frame, as against where
     * they have been told to be.
     *
     * Telemetry lands a few times a second and the screen draws sixty: taken
     * literally, everything steps rather than moves. Both are eased at the same
     * rate, so the model does not wander around the middle of the screen while
     * the camera catches up with it.
     */
    private val shownTarget = FloatArray(3)
    private var shownX = 0f
    private var shownY = 0f
    private var shownZ = 0f
    private var shownHeading = 0f
    private var shownPitch = 0f
    private var shownRoll = 0f
    private var placed = false

    private fun ease(from: Float, to: Float): Float = from + (to - from) * SMOOTHING

    /** The short way round, so a turn through north is not a lap of the compass. */
    private fun easeAngle(from: Float, to: Float): Float =
        juricabi.com.telemetry.utils.GeoUtils.turnTowards(from, to, SMOOTHING)

    /**
     * Follow where things have been put, unless they have been put somewhere
     * else entirely — a jump to your own location, or the first position of
     * all, should arrive rather than glide.
     */
    /**
     * Put the model where it has been told, at once, without easing to it.
     *
     * Easing assumes the next place is a fix or so away. When a whole batch of
     * flight arrives together — which is how a replay delivers it — the model
     * would otherwise walk through it while the flight is already drawn to the
     * end of the batch, so the flight runs on past the model in front of it.
     */
    /** Nothing to show until the next point arrives. */
    @Synchronized
    fun hideModel() {
        modelVisible = false
    }

    @Synchronized
    fun snapToTarget() {
        placed = false
    }

    /**
     * Whether the camera has just been put somewhere rather than having glided
     * there, in which case the ground under it is taken at once as well.
     */
    private var liftsSnap = false
    private var targetLift = 0f

    private fun settle() {
        val t = target
        val far = Math.max(200f, distance * 0.5f)
        val dx = t[0] - shownTarget[0]
        val dy = t[1] - shownTarget[1]
        val dz = t[2] - shownTarget[2]
        if (!placed || dx * dx + dy * dy + dz * dz > far * far) {
            placed = true
            liftsSnap = true
            shownTarget[0] = t[0]; shownTarget[1] = t[1]; shownTarget[2] = t[2]
            shownX = modelX; shownY = modelY; shownZ = modelZ
            shownHeading = modelHeading; shownPitch = modelPitch; shownRoll = modelRoll
            return
        }
        shownTarget[0] = ease(shownTarget[0], t[0])
        shownTarget[1] = ease(shownTarget[1], t[1])
        shownTarget[2] = ease(shownTarget[2], t[2])
        shownX = ease(shownX, modelX)
        shownY = ease(shownY, modelY)
        shownZ = ease(shownZ, modelZ)
        shownHeading = easeAngle(shownHeading, modelHeading)
        shownPitch = easeAngle(shownPitch, modelPitch)
        shownRoll = easeAngle(shownRoll, modelRoll)
    }

    /** Orbit: degrees round, degrees up, and how far out. */
    @Volatile var azimuth = 30f
    @Volatile var elevation = 28f
    @Volatile var distance = 1500f

    /**
     * Where something else wants the camera to look from, eased into rather
     * than snapped to. An aircraft's heading arrives many times a second and
     * wanders by a degree or two on every one of them; taken literally the
     * camera shakes. Not a number when nothing is steering it.
     */
    @Volatile var azimuthWanted = Float.NaN

    /**
     * Riding behind the model: the camera keeps to the far side of the heading
     * the model is *drawn* at, not the one last reported.
     *
     * Those are different things — the model is eased between attitudes, so
     * aiming the camera at the raw heading had the two turning at their own
     * rates and the camera never quite square behind it.
     */
    @Volatile var chasingModel = false

    /** How far the camera has been leaned out of that, in degrees. */
    @Volatile var chaseYaw = 0f

    /** As far out as the ground goes; set from the flight, not guessed. */
    @Volatile var maxDistance = 3000f
    @Volatile var target = floatArrayOf(0f, 0f, 0f)

    /**
     * No exaggeration. Heights were stretched by nearly half to make hills
     * read from above, which also made every flight look half again as high as
     * it was — and a height you cannot trust is worse than a flat looking hill.
     */


    @Volatile var trackColor = floatArrayOf(1f, 0.85f, 0.1f, 1f)

    /** The model's colour, from the same setting that tints the map marker. */
    @Volatile var modelColor = floatArrayOf(1f, 0.25f, 0.15f, 1f)

    /** Anything else worth drawing as lines: home, heading, plans, traffic. */
    class LineSet(
        val vertices: FloatArray,
        val red: Float, val green: Float, val blue: Float, val alpha: Float,
        val strip: Boolean,
        val width: Float,
        val onGround: Boolean
    )

    private class DrawnSet(val buffer: FloatBuffer, val count: Int, val set: LineSet)

    private var overlays: List<DrawnSet> = emptyList()

    @Synchronized
    /**
     * The flight plans, the traffic and the accuracy ring, as lines to draw.
     *
     * Refilled where the shape has not changed. This is called on every fix,
     * and a draped flight plan is thousands of floats: replacing every buffer
     * each time was megabytes a second of direct memory, which is freed only
     * when the collector gets round to it.
     */
    fun setOverlays(sets: List<LineSet>) {
        val standing = overlays
        val drawn = ArrayList<DrawnSet>()
        var i = 0
        for (set in sets) {
            if (set.vertices.size < 6) continue
            val count = set.vertices.size / 3
            val old = if (i < standing.size) standing[i] else null
            if (old != null && old.count == count && old.buffer.capacity() == set.vertices.size) {
                fill(old.buffer, set.vertices)
                drawn.add(DrawnSet(old.buffer, count, set))
            } else {
                drawn.add(DrawnSet(floats(set.vertices), count, set))
            }
            i++
        }
        overlays = drawn
    }


    private var modelBuffer: FloatBuffer? = null
    private var modelCount = 0
    private val modelMatrix = FloatArray(16)
    private val modelMvp = FloatArray(16)
    @Volatile private var modelVisible = false
    @Volatile private var modelX = 0f
    @Volatile private var modelY = 0f
    @Volatile private var modelZ = 0f
    @Volatile private var modelHeading = 0f
    @Volatile private var modelPitch = 0f
    @Volatile private var modelRoll = 0f
    @Volatile private var modelSize = 40f

    /**
     * Where the model is and which way it is going. Size is in metres, since a
     * dart drawn to scale would be a speck from any useful distance.
     */
    @Synchronized
    fun setModel(x: Float, y: Float, z: Float, headingDegrees: Float, size: Float,
                 pitchDegrees: Float = 0f, rollDegrees: Float = 0f) {
        modelX = x; modelY = y; modelZ = z
        modelHeading = headingDegrees
        modelPitch = pitchDegrees
        modelRoll = rollDegrees
        modelSize = size
        modelVisible = true
        if (modelBuffer == null || builtShape != modelShape) {
            val mesh = when (modelShape) {
                "plane" -> ModelMeshes.plane()
                "heli" -> ModelMeshes.heli()
                else -> ModelMeshes.quad()
            }
            modelBuffer = floats(mesh)
            // eight floats a vertex now, not three: dividing by three drew far
            // more triangles than the mesh has and read whatever lay past its
            // end, which is what tore the model apart
            modelCount = mesh.size / MODEL_FLOATS
            builtShape = modelShape
        }
    }

    /** "quad", "plane" or "heli": the same choice the map marker follows. */
    @Volatile var modelShape = "quad"
    private var builtShape = ""

    private var arrowBuffer: FloatBuffer? = null
    private var arrowCount = 0
    private var puckBuffer: FloatBuffer? = null
    private var puckCount = 0
    private val arrowMatrix = FloatArray(16)
    private val arrowMvp = FloatArray(16)
    /**
     * An arrow standing on the ground, with the ring of its accuracy round it.
     *
     * There are two: where this phone is, and — replaying a flight — where it
     * stood while that flight was recorded. They mean different things and are
     * drawn in different colours, and they can be a county apart.
     *
     * [heading] eases towards [headingTarget] as the thing it belongs to turns,
     * which is why each keeps its own: they turn independently.
     */
    private class Spot(val base: FloatArray, val ink: FloatArray) {
        @Volatile var visible = false

        /**
         * Whether direction is known and this is an arrow rather than a puck.
         *
         * A phone with no compass knows where it is and not which way it
         * faces. A puck keeps that known position visible without inventing a
         * north-facing arrow.
         */
        @Volatile var pointing = true
        @Volatile var x = 0f
        @Volatile var y = 0f
        @Volatile var z = 0f
        @Volatile var headingTarget = 0f
        var heading = 0f
        var ring: FloatBuffer? = null
        var ringCount = 0
    }

    private val me = Spot(
        floatArrayOf(0.42f, 0.76f, 1f), floatArrayOf(0.2f, 0.6f, 1f, 0.9f)
    )
    private val logged = Spot(
        floatArrayOf(1f, 0.62f, 0.13f), floatArrayOf(1f, 0.6f, 0.1f, 0.9f)
    )

    /**
     * The colours of the two arrows and the rings around them.
     *
     * Brighter than the same colour on the map, deliberately: satellite imagery
     * is dark greens and browns, and a colour that reads on a flat map sinks
     * into it.
     */
    fun setArrowColours(live: Int, logged: Int) {
        paint(me, live)
        paint(this.logged, logged)
    }

    private fun paint(spot: Spot, argb: Int) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        spot.base[0] = Math.min(1f, r * 1.15f + 0.06f)
        spot.base[1] = Math.min(1f, g * 1.15f + 0.06f)
        spot.base[2] = Math.min(1f, b * 1.15f + 0.06f)
        spot.ink[0] = r
        spot.ink[1] = g
        spot.ink[2] = b
        spot.ink[3] = 0.9f
    }

    /**
     * Where you are standing and which way you face.
     *
     * Position only: the size is worked out per frame from how far the camera
     * is, so the arrow holds its size on the screen the way a marker on a map
     * does. Fixed in metres it was a speck from high up and covered the hill
     * from close in.
     */
    @Synchronized
    /** Nothing known about this phone: the arrow goes, rather than moving. */
    fun hideMyLocation() {
        me.visible = false
    }

    fun setMyLocation(x: Float, y: Float, z: Float, headingDegrees: Float,
                      pointing: Boolean = true) {
        me.pointing = pointing
        place(me, x, y, z, headingDegrees)
    }

    /** Where the phone stood while the flight being replayed was recorded. */
    fun hideLoggedLocation() {
        logged.visible = false
    }

    fun setLoggedLocation(
        x: Float, y: Float, z: Float, headingDegrees: Float,
        pointing: Boolean = true
    ) {
        logged.pointing = pointing
        place(logged, x, y, z, headingDegrees)
    }

    /**
     * Under the lock for the mesh, which the two arrows share.
     *
     * One of them is placed by the compass, on the sensor thread, and the other
     * by a replay on the screen's — and the drawing thread reads the mesh under
     * this same lock. Built outside it, a buffer half-filled by one thread
     * could be handed to either of the others as though it were finished.
     */
    @Synchronized
    private fun place(spot: Spot, x: Float, y: Float, z: Float, headingDegrees: Float) {
        spot.x = x; spot.y = y; spot.z = z
        spot.headingTarget = headingDegrees
        spot.visible = true
        if (arrowBuffer == null) {
            val mesh = arrowMesh()
            arrowBuffer = floats(mesh)
            arrowCount = mesh.size / MODEL_FLOATS
        }
        if (puckBuffer == null) {
            val mesh = puckMesh()
            puckBuffer = floats(mesh)
            puckCount = mesh.size / MODEL_FLOATS
        }
    }

    /**
     * A unit arrow: nose along -z, swept corners, a raised spine.
     *
     * Built the way the model is, so the same shader lights it and inks in its
     * edges. Five corners and six faces, every edge of it a real one, so none
     * are marked as seams.
     */
    private fun arrowMesh(): FloatArray {
        val nose = floatArrayOf(0f, 0f, -1.6f)
        val left = floatArrayOf(-1f, 0f, 1f)
        val right = floatArrayOf(1f, 0f, 1f)
        val tail = floatArrayOf(0f, 0f, 0.4f)
        val spine = floatArrayOf(0f, 0.8f, 0.1f)
        val solid = ModelMeshes.Solid()
        solid.tri(nose, left, spine)
        solid.tri(nose, spine, right)
        solid.tri(left, tail, spine)
        solid.tri(tail, right, spine)
        solid.tri(nose, tail, left)
        solid.tri(nose, right, tail)
        return solid.build()
    }

    /** A low marker for a known place whose direction was not recorded. */
    private fun puckMesh(): FloatArray {
        val solid = ModelMeshes.Solid()
        solid.post(0f, 0f, 0.8f, 0f, 0.22f, 16)
        return solid.build()
    }

    /** The accuracy ring, already laid on the ground by whoever built it. */
    @Synchronized
    fun setAccuracyCircle(ring: FloatArray) {
        encircle(me, ring)
    }

    @Synchronized
    fun setLoggedCircle(ring: FloatArray) {
        encircle(logged, ring)
    }

    private fun encircle(spot: Spot, ring: FloatArray) {
        if (ring.size < 9) {
            spot.ring = null
            spot.ringCount = 0
            return
        }
        spot.ring = floats(ring)
        spot.ringCount = ring.size / 3
    }

    /** Ground height at a point in the local frame, so the camera can stay above it. */
    @Volatile var groundUnderCamera: ((Float, Float) -> Float?)? = null

    private var surfaceWidth = 1
    private var surfaceHeight = 1

    // ------------------------------------------------------------- feeding


    /**
     * One tile, as soon as it is built, without disturbing the others.
     *
     * The ground is built a tile at a time and shown as it arrives, so this is
     * how nearly all of it comes in. A tile offered twice — once for its shape
     * and again when its picture has been fetched — replaces the first.
     */
    @Synchronized
    fun offer(mesh: TerrainScene.TileMesh) {
        pending.add(mesh)
        for (i in submitted.indices) {
            if (submitted[i].key == mesh.key) {
                submitted[i] = mesh
                return
            }
        }
        submitted.add(mesh)
    }

    /**
     * Which tiles are still wanted; anything else is thrown away next frame.
     *
     * Handed an empty set, everything goes — which is what settling the
     * altitude reference needs, since that moves the whole world and the same
     * ground comes back with different heights under the same names.
     */
    @Synchronized
    fun keepOnly(keys: Set<Long>) {
        keep = HashSet(keys)
        keepChanged = true
        var i = 0
        while (i < submitted.size) {
            if (!keys.contains(submitted[i].key)) submitted.removeAt(i) else i++
        }
    }

    /**
     * The newest fix, added to all three at once.
     *
     * Rebuilding the whole flight is the tick's work — it is thinned, smoothed,
     * and its curtain is built from scratch — but the line has to keep up with
     * the model, and one more point is a handful of floats.
     */
    @Synchronized
    fun appendFlightPoint(x: Float, y: Float, z: Float, groundY: Float) {
        val air = trackBuffer ?: return
        val ground = shadowBuffer ?: return
        if (trackCount < 1 || shadowCount != trackCount) return
        if ((trackCount + 1) * 3 > air.capacity()) return
        if ((shadowCount + 1) * 3 > ground.capacity()) return

        val previous = (trackCount - 1) * 3
        val ax = air.get(previous)
        val ay = air.get(previous + 1)
        val az = air.get(previous + 2)
        val sx = ground.get(previous)
        val sy = ground.get(previous + 1)
        val sz = ground.get(previous + 2)

        // Written where they belong, rather than by walking the buffer there.
        //
        // The drawing thread hands these very buffers to the driver, which
        // reads each from wherever its position happens to be standing. A fix
        // landing in that moment — between the drawing thread setting the
        // position to nought and the driver reading it — left the position in
        // the middle of the flight, and the flight, its shadow or the curtain
        // was drawn from the middle of itself: a line to nowhere, for a frame,
        // for no reason anybody could see. Writing at an index leaves the
        // position alone, so the drawing thread is the only one that moves it.
        var at = trackCount * 3
        air.put(at, x); air.put(at + 1, y); air.put(at + 2, z)
        ground.put(at, x); ground.put(at + 1, groundY); ground.put(at + 2, z)

        val curtain = dropBuffer
        if (curtain != null && (dropCount + 6) * 3 <= curtain.capacity()) {
            at = dropCount * 3
            curtain.put(at, ax); curtain.put(at + 1, ay); curtain.put(at + 2, az)
            curtain.put(at + 3, sx); curtain.put(at + 4, sy); curtain.put(at + 5, sz)
            curtain.put(at + 6, x); curtain.put(at + 7, y); curtain.put(at + 8, z)
            curtain.put(at + 9, x); curtain.put(at + 10, y); curtain.put(at + 11, z)
            curtain.put(at + 12, sx); curtain.put(at + 13, sy); curtain.put(at + 14, sz)
            curtain.put(at + 15, x); curtain.put(at + 16, groundY); curtain.put(at + 17, z)
            dropCount += 6
            appendedQuads++
        }

        // counts last, so the drawing thread never sees a point that has not
        // been written yet
        trackCount++
        shadowCount++
        headGroundY = groundY
    }

    /**
     * The line home and the line ahead, drawn from where the model is drawn.
     *
     * Both start at the model, so both belong here rather than among the
     * overlays: built there, out of the last fix, they jumped ahead of it and
     * waited for the next one while the model glided between them.
     */
    @Synchronized
    fun setHomeLine(on: Boolean, x: Float, y: Float, z: Float,
                    r: Float, g: Float, b: Float, a: Float) {
        homeOn = on
        homeX = x; homeY = y; homeZ = z
        homeR = r; homeG = g; homeB = b; homeA = a
    }

    @Synchronized
    fun setHeadingLine(on: Boolean, r: Float, g: Float, b: Float, a: Float) {
        headingOn = on
        headR = r; headG = g; headB = b; headA = a
    }

    private fun fill(buffer: FloatBuffer, data: FloatArray) {
        buffer.position(0)
        buffer.put(data)
        buffer.position(0)
    }

    @Synchronized
    fun setTrack(track: FloatArray, shadow: FloatArray) {
        // with room for the fixes that arrive before the next rebuild
        trackBuffer = floatsWithRoom(track, SPARE_POINTS * 3)
        trackCount = track.size / 3
        shadowBuffer = floatsWithRoom(shadow, SPARE_POINTS * 3)
        shadowCount = shadow.size / 3

        // A curtain hanging from the flight down to its shadow, rather than a
        // ladder of separate rungs: height reads as a surface where it only
        // ever read as a hint before, and a translucent one does not bury the
        // ground it stands on.
        if (track.size != shadow.size || trackCount <= 1) {
            // Nothing to hang a curtain from. Without this the last one stayed
            // exactly where it was: jumping a replay back to the start empties
            // the flight, and the curtain of the flight that was there before
            // went on hanging in the air over the new one.
            dropBuffer = null
            dropCount = 0
        }
        if (track.size == shadow.size && trackCount > 1) {
            // one quad per point is more than a screen can show; a couple of
            // thousand is plenty for a whole flight
            val step = Math.max(1, trackCount / 2000)
            curtainStep = step
            appendedQuads = 0
            // Straight into the array it is going to live in. Growing a list of
            // boxed floats made fifty thousand objects of a curtain that is
            // rebuilt every time the flight gains a point.
            val quads = (trackCount - 1) / step + 1
            val array = FloatArray(quads * 18)
            var at = 0
            var i = 0
            while (i + step < trackCount && at + 18 <= array.size) {
                val a = i * 3
                val b = (i + step) * 3
                // two triangles: flight to shadow, along one step of the path
                array[at++] = track[a]; array[at++] = track[a + 1]; array[at++] = track[a + 2]
                array[at++] = shadow[a]; array[at++] = shadow[a + 1]; array[at++] = shadow[a + 2]
                array[at++] = track[b]; array[at++] = track[b + 1]; array[at++] = track[b + 2]

                array[at++] = track[b]; array[at++] = track[b + 1]; array[at++] = track[b + 2]
                array[at++] = shadow[a]; array[at++] = shadow[a + 1]; array[at++] = shadow[a + 2]
                array[at++] = shadow[b]; array[at++] = shadow[b + 1]; array[at++] = shadow[b + 2]
                i += step
            }
            // and close it to the final point: the loop stops a step short of
            // the end, which on a long flight is a stretch of missing curtain
            // exactly where the model is
            if (i < trackCount - 1 && at + 18 <= array.size) {
                val a = i * 3
                val b = (trackCount - 1) * 3
                array[at++] = track[a]; array[at++] = track[a + 1]; array[at++] = track[a + 2]
                array[at++] = shadow[a]; array[at++] = shadow[a + 1]; array[at++] = shadow[a + 2]
                array[at++] = track[b]; array[at++] = track[b + 1]; array[at++] = track[b + 2]

                array[at++] = track[b]; array[at++] = track[b + 1]; array[at++] = track[b + 2]
                array[at++] = shadow[a]; array[at++] = shadow[a + 1]; array[at++] = shadow[a + 2]
                array[at++] = shadow[b]; array[at++] = shadow[b + 1]; array[at++] = shadow[b + 2]
            }
            dropBuffer = floatsWithRoom(array, SPARE_POINTS * 18)
            dropCount = at / 3
        }
    }

    private fun floatsWithRoom(data: FloatArray, spare: Int): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect((data.size + spare) * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(data)
        buffer.position(0)
        return buffer
    }

    private fun floats(data: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(data).position(0)
        return buffer
    }

    private fun shorts(data: ShortArray): ShortBuffer {
        val buffer = ByteBuffer.allocateDirect(data.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        buffer.put(data).position(0)
        return buffer
    }

    // ------------------------------------------------------------ renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.09f, 0.11f, 0.14f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        terrainProgram = program(TERRAIN_VERTEX, TERRAIN_FRAGMENT)
        // even lines where the driver can measure a pixel, plain ones where
        // it cannot; the extension is old and common, but not promised
        modelProgram = program(MODEL_VERTEX, MODEL_FRAGMENT_EVEN)
        if (modelProgram == 0) modelProgram = program(MODEL_VERTEX, MODEL_FRAGMENT)
        lineProgram = program(LINE_VERTEX, LINE_FRAGMENT)
        // a new context throws away every texture and buffer we had, so put
        // the meshes back in the queue to be uploaded again
        var picturesLost = false
        synchronized(this) {
            tiles.clear()
            pending.clear()
            pending.addAll(submitted)
            // The geometry re-uploads from the meshes, but their pictures were
            // recycled the moment they first reached the card. Say so, and the
            // view fetches them back from the disk they were stitched from.
            picturesLost = submitted.any { it.texture?.isRecycled == true }
        }
        if (picturesLost) onPicturesLost?.invoke()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = Math.max(1, width)
        surfaceHeight = Math.max(1, height)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        // the projection is rebuilt per frame instead, from how far out we are
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        uploadPending()

        // Depth resolution is spent between the near and far planes, so a near
        // plane fixed at a few metres with the far one kilometres away left
        // almost none of it for the ground — which is why the arrow flickered
        // against the terrain from far out. Both now follow the camera.
        val ratio = surfaceWidth.toFloat() / surfaceHeight
        val near = Math.max(1f, distance / 200f)
        val far = Math.max(4000f, distance * 8f)
        Matrix.perspectiveM(projection, 0, 50f, ratio, near, far)

        settle()

        if (chasingModel) {
            azimuthWanted = ((-shownHeading + chaseYaw) % 360f + 360f) % 360f
        }

        val wanted = azimuthWanted
        if (!wanted.isNaN()) {
            azimuth = juricabi.com.telemetry.utils.GeoUtils.turnTowards(azimuth, wanted, 0.15f)
        }

        val az = Math.toRadians(azimuth.toDouble())
        val el = Math.toRadians(elevation.toDouble().coerceIn(3.0, 87.0))
        // The camera works in the same space the ground is drawn in, so the
        // live in that same stretched space. Placing it in unstretched metres
        // put it below hills it was supposed to clear — which is why it could
        // still end up inside them.
        // Eased, like the lift under the eye below, and for a stronger
        // reason: the ground under a point is not known until the tile holding
        // it is in memory, so during a load every tile that lands turns a "no
        // idea" into a height. Applied outright, the point the camera is
        // looking at jumped to each of them in a single frame — which is the
        // view lurching about over bare grey mesh while a log decodes.
        val targetFloorRaw = groundUnderCamera?.invoke(shownTarget[0], shownTarget[2])
        val wantTargetLift = if (targetFloorRaw == null) {
            0f
        } else {
            Math.max(0f, targetFloorRaw + 5f - shownTarget[1])
        }
        targetLift = if (liftsSnap) {
            wantTargetLift
        } else {
            targetLift + (wantTargetLift - targetLift) * 0.15f
        }
        val targetY = shownTarget[1] + targetLift

        val eyeX = shownTarget[0] + (distance * Math.cos(el) * Math.sin(az)).toFloat()
        var eyeY = targetY + (distance * Math.sin(el)).toFloat()
        val eyeZ = shownTarget[2] + (distance * Math.cos(el) * Math.cos(az)).toFloat()
        // never under the ground: it is opaque from below and the view becomes
        // a meaningless slab
        // Eased, not snapped. Riding behind the model puts the camera low,
        // where swinging it round the model runs it over ground of changing
        // height — and a lift applied the moment it is needed and dropped the
        // moment it is not is a camera that jumps every frame.
        val floorY = groundUnderCamera?.invoke(eyeX, eyeZ)
        val wantLift = if (floorY == null) 0f else {
            Math.max(0f, floorY + 40f - eyeY)
        }
        // and the same, so that arriving at a model does not then climb
        // slowly out of the hill it is standing behind
        floorLift = if (liftsSnap) wantLift else floorLift + (wantLift - floorLift) * 0.15f
        liftsSnap = false
        eyeY += floorLift
        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ,
            shownTarget[0], targetY, shownTarget[2], 0f, 1f, 0f)

        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)

        // The flight before the model, so the model is never painted over.
        //
        // Its curtain reaches all the way to where the model is drawn, which
        // means it passes through it; drawn afterwards, wherever that surface
        // was nearer to the eye than the model it covered it. Lines that really
        // are in front of the model still show, since they leave their depth
        // behind them and the model is drawn against it — only the curtain,
        // which deliberately leaves no depth, gives way.
        drawTerrain()
        drawLines()
        drawModelLit()
        drawMyArrow()
    }

    /**
     * Upload what is new and throw away what has gone.
     *
     * Extending the ground hands back the tiles already on screen along with
     * the new one. Uploading the lot meant deleting and re-sending nine 8MB
     * textures in a single frame, which is a freeze of the better part of a
     * second — and it happened every time the flight neared the edge of what
     * was loaded.
     */
    private fun uploadPending() {
        val meshes: List<TerrainScene.TileMesh>
        synchronized(this) {
            // a change of mind about what to keep is reason enough to run,
            // even with nothing new to put up
            if (pending.isEmpty() && !keepChanged) return
            keepChanged = false
            meshes = ArrayList(pending)
            pending.clear()
            // Whatever the scene no longer wants. It says so itself now, rather
            // than it being inferred from a submission being the whole set —
            // which it no longer is, since tiles arrive one at a time.
            val wanted = keep
            if (wanted != null) {
                val gone = ArrayList<Tile>()
                for (t in tiles) if (!wanted.contains(t.key)) gone.add(t)
                for (t in gone) {
                    if (t.textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(t.textureId), 0)
                    GLES20.glDeleteBuffers(2, intArrayOf(t.vertexBuffer, t.indexBuffer), 0)
                    tiles.remove(t)
                }
            }
        }
        for (mesh in meshes) {
            // The shape of this tile is already up. If a picture has arrived for
            // it since, that is all that needs sending — the ground itself has
            // not moved, and re-sending a megabyte of it would be for nothing.
            var standing: Tile? = null
            synchronized(this) {
                for (t in tiles) if (t.key == mesh.key) standing = t
            }
            val here = standing
            if (here != null) {
                val bitmap = mesh.texture
                if (here.textureId == 0 && bitmap != null && !bitmap.isRecycled) {
                    here.textureId = uploadTexture(bitmap)
                }
                continue
            }
            val bitmap = mesh.texture
            val texture = if (bitmap != null && !bitmap.isRecycled) {
                uploadTexture(bitmap)
            } else {
                0
            }
            val ids = IntArray(2)
            GLES20.glGenBuffers(2, ids, 0)
            val vertices = floats(mesh.vertices)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mesh.vertices.size * 4, vertices,
                GLES20.GL_STATIC_DRAW)
            val indices = shorts(mesh.indices)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 2, indices,
                GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
            synchronized(this) {
                tiles.add(Tile(mesh.key, ids[0], ids[1], mesh.indices.size, texture))
            }
        }
    }

    private fun uploadTexture(bitmap: android.graphics.Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        // Zoomed out, a 2048px texture lands on a few hundred pixels of screen,
        // and without a chain of smaller copies every one of them reads a
        // different corner of it: slow, and it shimmers.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        // The picture now lives on the card, and the heap copy was held only
        // against a lost context — half the ground's memory, kept for a rare
        // event. Let it go; a lost context asks the disk cache instead.
        bitmap.recycle()
        return ids[0]
    }

    private fun drawTerrain() {
        if (terrainProgram == 0) return
        GLES20.glUseProgram(terrainProgram)
        val aPosition = GLES20.glGetAttribLocation(terrainProgram, "aPosition")
        val aTexture = GLES20.glGetAttribLocation(terrainProgram, "aTexture")
        val aNormal = GLES20.glGetAttribLocation(terrainProgram, "aNormal")
        val uMvp = GLES20.glGetUniformLocation(terrainProgram, "uMvp")
        val uHasTexture = GLES20.glGetUniformLocation(terrainProgram, "uHasTexture")
        val uBase = GLES20.glGetUniformLocation(terrainProgram, "uBase")
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        // the ground's own colour, for a tile whose imagery has not arrived
        GLES20.glUniform3f(uBase, 0.45f, 0.44f, 0.40f)

        // The proper answer to two surfaces at the same depth: push the ground
        // back by a hair, in depth only, so anything lying on it wins without
        // being moved off it. The bias is worked out per fragment from the
        // slope and the depth resolution to hand, which is why it holds at
        // every zoom where a fixed lift could not — and nothing floats.
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glPolygonOffset(2.5f, 8f)

        val snapshot: List<Tile>
        synchronized(this) { snapshot = ArrayList(tiles) }
        val stride = FLOATS_PER_VERTEX * 4
        for (tile in snapshot) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tile.vertexBuffer)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, tile.indexBuffer)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, stride, 0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aTexture, 2, GLES20.GL_FLOAT, false, stride, 3 * 4)
            GLES20.glEnableVertexAttribArray(aTexture)
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, stride, 5 * 4)
            GLES20.glEnableVertexAttribArray(aNormal)

            if (tile.textureId != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tile.textureId)
                GLES20.glUniform1f(uHasTexture, 1f)
            } else {
                GLES20.glUniform1f(uHasTexture, 0f)
            }
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, tile.count,
                GLES20.GL_UNSIGNED_SHORT, 0)

            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexture)
            GLES20.glDisableVertexAttribArray(aNormal)
        }
        // everything after this draws from ordinary buffers, which a bound
        // vertex buffer would silently override
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
    }

    /**
     * A whisker above the terrain, no more, and no longer growing with
     * distance.
     *
     * The ground is pushed back in depth while it is drawn, and that is what
     * keeps these clear of it. This is only so a line lying across a slope does
     * not weave in and out of the surface between its vertices. Scaling it with
     * the camera made the ring float metres above the ground it belongs to.
     */
    private fun groundLift(): Float = 0.25f

    private fun drawLines() {
        if (lineProgram == 0) return
        GLES20.glUseProgram(lineProgram)
        val aPosition = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        val uMvp = GLES20.glGetUniformLocation(lineProgram, "uMvp")
        val uColor = GLES20.glGetUniformLocation(lineProgram, "uColor")
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glEnableVertexAttribArray(aPosition)
        // Every line here is given an alpha — the shadow, the curtain, the
        // accuracy ring, and the traffic posts most of all, which are meant to
        // be faint. Without this they all drew solid and the alpha was a lie.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val track: FloatBuffer?
        val shadow: FloatBuffer?
        val drops: FloatBuffer?
        val tCount: Int
        val sCount: Int
        val dCount: Int
        synchronized(this) {
            track = trackBuffer; tCount = trackCount
            shadow = shadowBuffer; sCount = shadowCount
            drops = dropBuffer; dCount = dropCount
        }

        // Anything lying on the ground fights with it once the camera is far
        // enough out that a metre is below what the depth buffer can tell
        // apart. The ground is pushed back in depth as it is drawn, which is
        // what keeps these clear of it; this is the whisker on top.
        val lift = groundLift()
        Matrix.setIdentityM(liftMatrix, 0)
        Matrix.translateM(liftMatrix, 0, 0f, lift, 0f)
        Matrix.multiplyMM(liftMvp, 0, mvp, 0, liftMatrix, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, liftMvp, 0)

        // Each of the three drawn up to the point before last, and then on to
        // the model — which is always somewhere along that final stretch, since
        // the flight ends at the newest fix and the model is easing towards it.
        val joined = track != null && shadow != null && modelVisible &&
            tCount >= 2 && sCount == tCount

        // How far along the flight the model has actually got.
        //
        // Live it is always within the last stretch, since one point arrives at
        // a time. A replay hands over a batch at once, so the flight can run
        // several points past where the model is — and drawn to its end, it
        // stands in front of the model instead of behind it. Walking back to
        // the stretch the model is really on holds for both.
        var reached = tCount - 1
        if (joined) {
            while (reached >= 1) {
                val a = (reached - 1) * 3
                val b = reached * 3
                val abx = track!!.get(b) - track.get(a)
                val aby = track.get(b + 1) - track.get(a + 1)
                val abz = track.get(b + 2) - track.get(a + 2)
                val amx = shownX - track.get(a)
                val amy = shownY - track.get(a + 1)
                val amz = shownZ - track.get(a + 2)
                if (abx * amx + aby * amy + abz * amz >= 0f) break
                reached--
            }
            if (reached < 1) reached = 1
        }
        val skipped = if (joined) tCount - reached else 0
        val airCount = if (joined) reached else tCount
        val groundCount = if (joined) reached else sCount
        // How much of the curtain to hold back, counted in quads.
        //
        // The flight is thinned before its curtain is built — one quad every
        // few points on a long flight — while the quads added since that
        // rebuild are one per point. So the points the model has not reached
        // yet are quads at the tail and a fraction of a quad each further
        // back, and taking one quad per point removed several times too much:
        // a gap behind the model that grew with the length of the flight.
        val curtainCount = if (joined && dCount >= 6) {
            val tail = Math.min(skipped, appendedQuads)
            val body = (skipped - tail) / Math.max(1, curtainStep)
            dCount - Math.min(tail + body, dCount / 6) * 6
        } else {
            dCount
        }

        if (joined) {
            val previous = (reached - 1) * 3
            val ax = track!!.get(previous)
            val ay = track.get(previous + 1)
            val az = track.get(previous + 2)
            val sx = shadow!!.get(previous)
            val sy = shadow.get(previous + 1)
            val sz = shadow.get(previous + 2)
            val bx: Float
            val by: Float
            val bz: Float
            val groundY: Float
            synchronized(this) {
                bx = shownX; by = shownY; bz = shownZ; groundY = headGroundY
            }
            headQuad[0] = ax; headQuad[1] = ay; headQuad[2] = az
            headQuad[3] = sx; headQuad[4] = sy; headQuad[5] = sz
            headQuad[6] = bx; headQuad[7] = by; headQuad[8] = bz
            headQuad[9] = bx; headQuad[10] = by; headQuad[11] = bz
            headQuad[12] = sx; headQuad[13] = sy; headQuad[14] = sz
            headQuad[15] = bx; headQuad[16] = groundY; headQuad[17] = bz
            fill(headQuadBuffer, headQuad)
        }

        if (shadow != null && groundCount > 1) {
            // its own width, rather than whatever the last pass left set: the
            // shadow changed thickness as other lines came and went
            GLES20.glLineWidth(2f)
            shadow.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, shadow)
            // the route colour darkened, rather than an anonymous black line
            GLES20.glUniform4f(uColor, trackColor[0] * 0.45f, trackColor[1] * 0.45f,
                trackColor[2] * 0.45f, 0.85f)
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, groundCount)
        }
        if (joined) {
            leaderPair[0] = headQuad[3]; leaderPair[1] = headQuad[4]; leaderPair[2] = headQuad[5]
            leaderPair[3] = headQuad[15]; leaderPair[4] = headQuad[16]; leaderPair[5] = headQuad[17]
            fill(leaderPairBuffer, leaderPair)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12,
                leaderPairBuffer)
            GLES20.glLineWidth(2f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        }
        if (drops != null && curtainCount > 2 || joined) {
            GLES20.glUniform4f(uColor, trackColor[0], trackColor[1], trackColor[2], 0.18f)
            // translucent, so what is behind it must still be drawn
            // no depth writing: the curtain is see through, so what is behind it
            // has to keep drawing, and it must not hide the flight above it
            GLES20.glDepthMask(false)
            if (drops != null && curtainCount > 2) {
                drops.position(0)
                GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, drops)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, curtainCount)
            }
            if (joined) {
                GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12,
                    headQuadBuffer)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
            }
            GLES20.glDepthMask(true)
        }

        if (modelVisible && homeOn) {
            synchronized(this) {
                leaderPair[0] = shownX; leaderPair[1] = shownY; leaderPair[2] = shownZ
                leaderPair[3] = homeX; leaderPair[4] = homeY; leaderPair[5] = homeZ
            }
            fill(leaderPairBuffer, leaderPair)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12,
                leaderPairBuffer)
            GLES20.glUniform4f(uColor, homeR, homeG, homeB, homeA)
            GLES20.glLineWidth(3f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        }
        if (modelVisible && headingOn) {
            synchronized(this) {
                val radians = Math.toRadians(shownHeading.toDouble())
                leaderPair[0] = shownX; leaderPair[1] = shownY; leaderPair[2] = shownZ
                leaderPair[3] = shownX + (1000.0 * Math.sin(radians)).toFloat()
                leaderPair[4] = shownY
                leaderPair[5] = shownZ - (1000.0 * Math.cos(radians)).toFloat()
            }
            fill(leaderPairBuffer, leaderPair)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12,
                leaderPairBuffer)
            GLES20.glUniform4f(uColor, headR, headG, headB, headA)
            GLES20.glLineWidth(2f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        }

        // the track and the model are up in the air, so they go back to the
        // plain matrix
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)

        val extras: List<DrawnSet>
        synchronized(this) { extras = overlays }
        for (extra in extras) {
            GLES20.glUniformMatrix4fv(uMvp, 1, false,
                if (extra.set.onGround) liftMvp else mvp, 0)
            extra.buffer.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, extra.buffer)
            GLES20.glUniform4f(uColor, extra.set.red, extra.set.green, extra.set.blue,
                extra.set.alpha)
            GLES20.glLineWidth(extra.set.width)
            GLES20.glDrawArrays(
                if (extra.set.strip) GLES20.GL_LINE_STRIP else GLES20.GL_LINES,
                0, extra.count)
        }
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)

        for (spot in arrayOf(me, logged)) {
            val ring: FloatBuffer?
            val rCount: Int
            synchronized(this) {
                ring = if (spot.visible) spot.ring else null
                rCount = spot.ringCount
            }
            if (ring == null || rCount <= 2) continue
            GLES20.glUniformMatrix4fv(uMvp, 1, false, liftMvp, 0)
            ring.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, ring)

            // Filled as well as drawn round, the way the map fills it. A ring
            // that is only an outline reads as somewhere to go; filled, it
            // reads as how well the place inside it is known, which is what it
            // is for.
            //
            // A fan from the first point on the rim rather than from a centre,
            // because the centre is not in the buffer — and a fan from any
            // corner fills a convex shape, which a circle is. Translucent, and
            // writing no depth: the ground under it and the arrow standing in
            // it both have to keep drawing through it.
            GLES20.glUniform4f(uColor, spot.ink[0], spot.ink[1], spot.ink[2], 0.15f)
            GLES20.glDepthMask(false)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, rCount)
            GLES20.glDepthMask(true)

            GLES20.glUniform4f(uColor, spot.ink[0], spot.ink[1], spot.ink[2], spot.ink[3])
            GLES20.glLineWidth(3f)
            GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, rCount)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        }
        if (track != null && airCount > 1) {
            // As far as the model has got, like its shadow and its curtain: it
            // was the one piece of the flight still drawn to the very end, so
            // it alone ran on in front of the model.
            track.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, track)
            GLES20.glUniform4f(uColor, trackColor[0], trackColor[1], trackColor[2], 1f)
            GLES20.glLineWidth(4f)
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, airCount)
        }
        if (joined) {
            // and on to the model, which is the stretch it stops short of
            leaderPair[0] = headQuad[0]; leaderPair[1] = headQuad[1]; leaderPair[2] = headQuad[2]
            leaderPair[3] = headQuad[6]; leaderPair[4] = headQuad[7]; leaderPair[5] = headQuad[8]
            fill(leaderPairBuffer, leaderPair)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12,
                leaderPairBuffer)
            GLES20.glUniform4f(uColor, trackColor[0], trackColor[1], trackColor[2], 1f)
            GLES20.glLineWidth(4f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        }
        GLES20.glDisableVertexAttribArray(aPosition)
        // put back what the ground and the model expect to find
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Drawn with the lit shader rather than the flat one.
     *
     * One colour with no shading makes any solid read as a silhouette, so arms,
     * motors and a fuselage were shapes nobody could see. Borrowing the program
     * the ground uses gives the model a light, at the cost of carrying normals.
     */
    private fun drawModelLit() {
        val buffer: FloatBuffer?
        // count and buffer together: they are replaced as a pair when the
        // shape changes, and a new count against an old buffer reads off its end
        val count: Int
        synchronized(this) {
            buffer = if (modelVisible) modelBuffer else null
            count = modelCount
        }
        if (buffer == null || count < 3 || modelProgram == 0) return

        GLES20.glUseProgram(modelProgram)
        val aPosition = GLES20.glGetAttribLocation(modelProgram, "aPosition")
        val aCorner = GLES20.glGetAttribLocation(modelProgram, "aCorner")
        val aNormal = GLES20.glGetAttribLocation(modelProgram, "aNormal")
        val uMvp = GLES20.glGetUniformLocation(modelProgram, "uMvp")
        val uBase = GLES20.glGetUniformLocation(modelProgram, "uBase")
        GLES20.glUniform1f(GLES20.glGetUniformLocation(modelProgram, "uInk"), 1.3f)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, shownX, shownY, shownZ)
        // yaw, then pitch, then roll — the order an aircraft's attitude is
        // built in, so a banked turn looks like a banked turn
        Matrix.rotateM(modelMatrix, 0, -shownHeading, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, shownPitch, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, -shownRoll, 0f, 0f, 1f)
        // undo the vertical exaggeration on the dart itself, or it grows a
        // taller fin the more the ground is stretched
        // Held at a size on screen rather than in metres — a model drawn to
        // scale is invisible from anywhere useful — but a good deal smaller
        // than it was, which made a quad look the size of a hangar.
        // the same fraction of the distance the position arrow uses, so the two
        // read as one family rather than two scales
        val drawSize = Math.max(3f, distance * 0.02f)
        Matrix.scaleM(modelMatrix, 0, drawSize, drawSize, drawSize)
        Matrix.multiplyMM(modelMvp, 0, mvp, 0, modelMatrix, 0)

        val stride = MODEL_FLOATS * 4
        buffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(aPosition)
        buffer.position(3)
        GLES20.glVertexAttribPointer(aCorner, 3, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(aCorner)
        buffer.position(6)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(aNormal)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, modelMvp, 0)
        GLES20.glUniform3f(uBase, modelColor[0], modelColor[1], modelColor[2])
        // The track ends inside the model, so the two share depths where they
        // cross and flickered against each other. Pulling the model a hair
        // forward settles which one wins, every frame.
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glPolygonOffset(-2f, -4f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aCorner)
        GLES20.glDisableVertexAttribArray(aNormal)
    }

    /**
     * The arrow for where you are standing, drawn the way the model is: lit,
     * and with its own edges inked in, so the two read as one family.
     */
    private fun drawMyArrow() {
        drawArrow(logged)
        // and this phone pulled a hair in front of it, the way the model is
        // pulled in front of the track it ends inside.
        //
        // Replaying a flight from the field it was flown at stands the two
        // arrows on the same spot. At equal depth nothing decides between them,
        // so which one survived came down to a metre of GPS error and changed
        // from one replay to the next — and it was as often the place you stood
        // last week as the place you are standing now. A bias rather than a
        // lift: the arrow does not move, so it cannot be seen to float, and the
        // shift is a few of the depth buffer's own steps — far too little to
        // show through the hill in front of it.
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glPolygonOffset(-2f, -4f)
        drawArrow(me)
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
    }

    private fun drawArrow(spot: Spot) {
        var mine: FloatBuffer? = null
        var count = 0
        var pointing = false
        synchronized(this) {
            pointing = spot.pointing
            if (spot.visible) {
                mine = if (pointing) arrowBuffer else puckBuffer
                count = if (pointing) arrowCount else puckCount
            }
        }
        val buffer = mine ?: return
        if (modelProgram == 0) return

        // eased towards the compass rather than snapped to it, so it turns
        // like the needle on the map instead of twitching
        if (pointing) {
            spot.heading = juricabi.com.telemetry.utils.GeoUtils.turnTowards(
                spot.heading, spot.headingTarget, 0.12f)
        }

        // A fraction of the distance out, so it is the same size on screen
        // however far the camera is — and a larger fraction than it reads as,
        // because an arrow that is exactly to scale is a speck the moment the
        // whole flight is in view, which is when it is most worth seeing.
        val size = Math.max(2f, distance * 0.02f)
        // Clearance in proportion, because the arrow lies flat: zoomed out it
        // is tens of metres across, and a fixed quarter of a metre left its
        // uphill half buried in any slope.
        val lift = groundLift() + size * (if (pointing) 0.3f else 0.08f)
        Matrix.setIdentityM(arrowMatrix, 0)
        Matrix.translateM(arrowMatrix, 0, spot.x, spot.y + lift, spot.z)
        if (pointing) Matrix.rotateM(arrowMatrix, 0, -spot.heading, 0f, 1f, 0f)
        Matrix.scaleM(arrowMatrix, 0, size, size, size)
        Matrix.multiplyMM(arrowMvp, 0, mvp, 0, arrowMatrix, 0)

        GLES20.glUseProgram(modelProgram)
        val aPosition = GLES20.glGetAttribLocation(modelProgram, "aPosition")
        val aCorner = GLES20.glGetAttribLocation(modelProgram, "aCorner")
        val aNormal = GLES20.glGetAttribLocation(modelProgram, "aNormal")
        val stride = MODEL_FLOATS * 4
        buffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(aPosition)
        buffer.position(3)
        GLES20.glVertexAttribPointer(aCorner, 3, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(aCorner)
        buffer.position(6)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(aNormal)

        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(modelProgram, "uMvp"), 1, false, arrowMvp, 0)
        // A thinner line and a brighter blue. The arrow is drawn much smaller
        // than the model, so an edge of the same weight took up most of it and
        // turned it dark.
        GLES20.glUniform1f(GLES20.glGetUniformLocation(modelProgram, "uInk"), 0.8f)
        // brighter than the marker on the map: satellite imagery is dark
        // greens and browns, and a deeper blue disappeared into it
        GLES20.glUniform3f(
            GLES20.glGetUniformLocation(modelProgram, "uBase"),
            spot.base[0], spot.base[1], spot.base[2])
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aCorner)
        GLES20.glDisableVertexAttribArray(aNormal)
    }

    private fun program(vertexSource: String, fragmentSource: String): Int {
        val vertex = shader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertex == 0 || fragment == 0) return 0
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            GLES20.glDeleteProgram(id)
            return 0
        }
        return id
    }

    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            GLES20.glDeleteShader(id)
            return 0
        }
        return id
    }
}
