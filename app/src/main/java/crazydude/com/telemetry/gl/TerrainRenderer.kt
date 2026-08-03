package crazydude.com.telemetry.gl

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
     * and nothing was left to upload.
     */
    private val submitted = ArrayList<TerrainScene.TileMesh>()

    private var trackBuffer: FloatBuffer? = null
    private var trackCount = 0
    private var shadowBuffer: FloatBuffer? = null
    private var shadowCount = 0
    private var dropBuffer: FloatBuffer? = null
    private var dropCount = 0

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
    private fun easeAngle(from: Float, to: Float): Float {
        var turn = to - from
        while (turn > 180f) turn -= 360f
        while (turn < -180f) turn += 360f
        return ((from + turn * SMOOTHING) % 360f + 360f) % 360f
    }

    /**
     * Follow where things have been put, unless they have been put somewhere
     * else entirely — a jump to your own location, or the first position of
     * all, should arrive rather than glide.
     */
    private fun settle() {
        val t = target
        val far = Math.max(200f, distance * 0.5f)
        val dx = t[0] - shownTarget[0]
        val dy = t[1] - shownTarget[1]
        val dz = t[2] - shownTarget[2]
        if (!placed || dx * dx + dy * dy + dz * dz > far * far) {
            placed = true
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

    /** As far out as the ground goes; set from the flight, not guessed. */
    @Volatile var maxDistance = 3000f
    @Volatile var target = floatArrayOf(0f, 0f, 0f)

    /**
     * No exaggeration. Heights were stretched by nearly half to make hills
     * read from above, which also made every flight look half again as high as
     * it was — and a height you cannot trust is worse than a flat looking hill.
     */
    @Volatile var verticalScale = 1.0f

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
    fun setOverlays(sets: List<LineSet>) {
        val drawn = ArrayList<DrawnSet>()
        for (set in sets) {
            if (set.vertices.size < 6) continue
            drawn.add(DrawnSet(floats(set.vertices), set.vertices.size / 3, set))
        }
        overlays = drawn
    }

    private var markerBuffer: FloatBuffer? = null
    private var markerCount = 0

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
            val mesh = if (modelShape == "plane") plane() else quad()
            modelBuffer = floats(mesh)
            // eight floats a vertex now, not three: dividing by three drew far
            // more triangles than the mesh has and read whatever lay past its
            // end, which is what tore the model apart
            modelCount = mesh.size / MODEL_FLOATS
            builtShape = modelShape
        }
    }

    /** "quad" or "plane": the same choice the map marker follows. */
    @Volatile var modelShape = "quad"
    private var builtShape = ""

    /**
     * Geometry for the model: position, which corner of its triangle each
     * vertex is, and a normal. The normal is what the light reads, and is the
     * whole difference between a model and a silhouette; the corner weights are
     * what let one pass both fill a face and draw its edges.
     */
    private class Solid {
        val out = ArrayList<Float>()

        /**
         * [hide] names edges that are not really there. Bit j holds the weight
         * of the edge opposite vertex j at one, so a fragment never approaches
         * a border there and the shader draws no line.
         *
         * A rectangle is two triangles with a seam down the middle. The seam is
         * not an edge of the model, and drawing it puts a diagonal across every
         * flat face.
         */
        fun tri(a: FloatArray, b: FloatArray, c: FloatArray, hide: Int = 0) {
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
            if (len > 0.00001f) { nx /= len; ny /= len; nz /= len }
            for (i in 0..2) {
                val p = when (i) { 0 -> a; 1 -> b; else -> c }
                out.add(p[0]); out.add(p[1]); out.add(p[2])
                // one at its own corner, nought at the others: the weights fall
                // to nought along the far edge, which is how near one is measured
                for (j in 0..2) {
                    out.add(if (j == i || (hide shr j) and 1 == 1) 1f else 0f)
                }
                out.add(nx); out.add(ny); out.add(nz)
            }
        }

        /** A flat four cornered face, seamless: only its border is an edge. */
        fun face(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) {
            tri(a, b, c, 1 shl 1)
            tri(a, c, d, 1 shl 2)
        }

        /** A box, from its two opposite corners. */
        fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
            val a = floatArrayOf(x0, y0, z0); val b = floatArrayOf(x1, y0, z0)
            val c = floatArrayOf(x1, y0, z1); val d = floatArrayOf(x0, y0, z1)
            val e = floatArrayOf(x0, y1, z0); val f = floatArrayOf(x1, y1, z0)
            val g = floatArrayOf(x1, y1, z1); val h = floatArrayOf(x0, y1, z1)
            face(e, f, g, h)
            face(a, d, c, b)
            face(a, b, f, e)
            face(d, h, g, c)
            face(a, e, h, d)
            face(b, c, g, f)
        }

        /** A box laid along a direction in the ground plane: an arm, or a wing. */
        fun arm(dirX: Float, dirZ: Float, from: Float, to: Float,
                halfWidth: Float, y0: Float, y1: Float, alongZ: Float = 0f) {
            val px = -dirZ
            val pz = dirX
            fun at(along: Float, side: Float, y: Float) = floatArrayOf(
                dirX * along + px * side, y, dirZ * along + pz * side + alongZ)
            val a = at(from, -halfWidth, y0); val b = at(to, -halfWidth, y0)
            val c = at(to, halfWidth, y0);    val d = at(from, halfWidth, y0)
            val e = at(from, -halfWidth, y1); val f = at(to, -halfWidth, y1)
            val g = at(to, halfWidth, y1);    val h = at(from, halfWidth, y1)
            face(e, f, g, h)
            face(a, d, c, b)
            face(a, b, f, e)
            face(d, h, g, c)
            face(a, e, h, d)
            face(b, c, g, f)
        }

        /** A short many sided post: a motor, or a propeller when it is flat. */
        fun post(cx: Float, cz: Float, radius: Float, y0: Float, y1: Float, sides: Int) {
            val spokes = (1 shl 1) or (1 shl 2)
            for (i in 0 until sides) {
                val a0 = 2.0 * Math.PI * i / sides
                val a1 = 2.0 * Math.PI * (i + 1) / sides
                val x0 = cx + (radius * Math.cos(a0)).toFloat()
                val z0 = cz + (radius * Math.sin(a0)).toFloat()
                val x1 = cx + (radius * Math.cos(a1)).toFloat()
                val z1 = cz + (radius * Math.sin(a1)).toFloat()
                face(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z1),
                     floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z0))
                // flat caps, drawn as a fan from the centre. Only the rim is an
                // edge — the spokes would draw a cartwheel on every motor.
                tri(floatArrayOf(cx, y1, cz), floatArrayOf(x0, y1, z0),
                    floatArrayOf(x1, y1, z1), spokes)
                tri(floatArrayOf(cx, y0, cz), floatArrayOf(x1, y0, z1),
                    floatArrayOf(x0, y0, z0), spokes)
            }
        }

        fun build(): FloatArray {
            val array = FloatArray(out.size)
            for (i in out.indices) array[i] = out[i]
            return array
        }
    }

    /** A quad: four arms out to motors, propeller discs, a body with a nose. */
    private fun quad(): FloatArray {
        val s = Solid()
        val d = 0.7071f
        for (c in arrayOf(floatArrayOf(d, -d), floatArrayOf(-d, -d),
                          floatArrayOf(d, d), floatArrayOf(-d, d))) {
            s.arm(c[0], c[1], 0.15f, 1f, 0.07f, -0.03f, 0.05f)
            // into the arm, not resting on it: two faces at one height are a
            // coin toss for the depth buffer, and it comes down differently
            // from one frame to the next
            s.post(c[0], c[1], 0.13f, 0.01f, 0.22f, 8)
            s.post(c[0], c[1], 0.42f, 0.23f, 0.25f, 10)
        }
        s.box(-0.28f, -0.10f, -0.30f, 0.28f, 0.20f, 0.42f)
        s.box(-0.16f, -0.05f, -0.62f, 0.16f, 0.10f, -0.28f)
        s.box(-0.18f, 0.15f, -0.18f, 0.18f, 0.32f, 0.20f)
        return s.build()
    }

    /** A plane: fuselage, swept wings, a fin and a tailplane. */
    private fun plane(): FloatArray {
        val s = Solid()
        s.box(-0.11f, -0.08f, -0.95f, 0.11f, 0.12f, 0.75f)
        s.box(-0.07f, -0.05f, -1.25f, 0.07f, 0.07f, -0.90f)
        s.box(-0.16f, 0.07f, -0.35f, 0.16f, 0.26f, 0.15f)
        s.arm(1f, 0f, 0.10f, 1.35f, 0.28f, -0.02f, 0.04f)
        s.arm(-1f, 0f, 0.10f, 1.35f, 0.28f, -0.02f, 0.04f)
        // the tailplane belongs at the tail. It was built in the middle, inside
        // the wing and sharing its top face, which both hid it and left the two
        // fighting over which was in front.
        s.arm(1f, 0f, 0.05f, 0.5f, 0.13f, 0f, 0.03f, 0.60f)
        s.arm(-1f, 0f, 0.05f, 0.5f, 0.13f, 0f, 0.03f, 0.60f)
        s.box(-0.04f, 0.10f, 0.45f, 0.04f, 0.55f, 0.78f)
        return s.build()
    }


    private var circleBuffer: FloatBuffer? = null
    private var circleCount = 0

    private var arrowBuffer: FloatBuffer? = null
    private val arrowMatrix = FloatArray(16)
    private val arrowMvp = FloatArray(16)
    @Volatile private var myVisible = false
    @Volatile private var myX = 0f
    @Volatile private var myY = 0f
    @Volatile private var myZ = 0f
    @Volatile private var myHeadingTarget = 0f
    private var myHeading = 0f

    /**
     * Where you are standing and which way you face.
     *
     * Position only: the size is worked out per frame from how far the camera
     * is, so the arrow holds its size on the screen the way a marker on a map
     * does. Fixed in metres it was a speck from high up and covered the hill
     * from close in.
     */
    @Synchronized
    fun setMyLocation(x: Float, y: Float, z: Float, headingDegrees: Float) {
        myX = x; myY = y; myZ = z
        myHeadingTarget = headingDegrees
        myVisible = true
        if (arrowBuffer == null) arrowBuffer = floats(arrowMesh())
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
        val solid = Solid()
        solid.tri(nose, left, spine)
        solid.tri(nose, spine, right)
        solid.tri(left, tail, spine)
        solid.tri(tail, right, spine)
        solid.tri(nose, tail, left)
        solid.tri(nose, right, tail)
        return solid.build()
    }

    /** The accuracy ring, already laid on the ground by whoever built it. */
    @Synchronized
    fun setAccuracyCircle(ring: FloatArray) {
        if (ring.size < 9) {
            circleBuffer = null
            circleCount = 0
            return
        }
        circleBuffer = floats(ring)
        circleCount = ring.size / 3
    }

    /** A post at a place worth seeing from the air, such as where you are standing. */
    @Synchronized
    fun setMarker(x: Float, groundY: Float, z: Float, height: Float) {
        markerBuffer = floats(floatArrayOf(x, groundY, z, x, groundY + height, z))
        markerCount = 2
    }

    /** Ground height at a point in the local frame, so the camera can stay above it. */
    @Volatile var groundUnderCamera: ((Float, Float) -> Float?)? = null

    private var surfaceWidth = 1
    private var surfaceHeight = 1

    // ------------------------------------------------------------- feeding

    /** Called off the GL thread; uploaded on the next frame. */
    @Synchronized
    fun submit(meshes: List<TerrainScene.TileMesh>) {
        pending.clear()
        pending.addAll(meshes)
        submitted.clear()
        submitted.addAll(meshes)
    }

    @Synchronized
    fun setTrack(track: FloatArray, shadow: FloatArray) {
        trackBuffer = floats(track)
        trackCount = track.size / 3
        shadowBuffer = floats(shadow)
        shadowCount = shadow.size / 3

        // A curtain hanging from the flight down to its shadow, rather than a
        // ladder of separate rungs: height reads as a surface where it only
        // ever read as a hint before, and a translucent one does not bury the
        // ground it stands on.
        if (track.size == shadow.size && trackCount > 1) {
            // one quad per point is more than a screen can show; a couple of
            // thousand is plenty for a whole flight
            val step = Math.max(1, trackCount / 2000)
            // Straight into the array it is going to live in. Growing a list of
            // boxed floats made fifty thousand objects of a curtain that is
            // rebuilt every time the flight gains a point.
            val quads = (trackCount - 1) / step
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
            dropBuffer = floats(array)
            dropCount = at / 3
        }
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
        synchronized(this) {
            tiles.clear()
            pending.clear()
            pending.addAll(submitted)
        }
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

        val wanted = azimuthWanted
        if (!wanted.isNaN()) {
            var turn = wanted - azimuth
            while (turn > 180f) turn -= 360f
            while (turn < -180f) turn += 360f
            azimuth = ((azimuth + turn * 0.15f) % 360f + 360f) % 360f
        }

        val az = Math.toRadians(azimuth.toDouble())
        val el = Math.toRadians(elevation.toDouble().coerceIn(3.0, 87.0))
        // The ground is drawn stretched by verticalScale, so the camera has to
        // live in that same stretched space. Placing it in unstretched metres
        // put it below hills it was supposed to clear — which is why it could
        // still end up inside them.
        val targetFloorRaw = groundUnderCamera?.invoke(shownTarget[0], shownTarget[2])
        val targetYRaw = if (targetFloorRaw != null && shownTarget[1] < targetFloorRaw + 5f) {
            targetFloorRaw + 5f
        } else {
            shownTarget[1]
        }
        val targetY = targetYRaw * verticalScale

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
            Math.max(0f, floorY * verticalScale + 40f - eyeY)
        }
        floorLift += (wantLift - floorLift) * 0.15f
        eyeY += floorLift
        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ,
            shownTarget[0], targetY, shownTarget[2], 0f, 1f, 0f)

        // the exaggeration lives in the matrix, so nothing has to be rebuilt,
        // and the camera above was placed in the same stretched space
        val scaled = FloatArray(16)
        Matrix.setIdentityM(scaled, 0)
        Matrix.scaleM(scaled, 0, 1f, verticalScale, 1f)
        val vm = FloatArray(16)
        Matrix.multiplyMM(vm, 0, view, 0, scaled, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, vm, 0)

        drawTerrain()
        drawModelLit()
        drawMyArrow()
        drawLines()
    }

    /**
     * Upload what is new and throw away what has gone.
     *
     * Extending the ground hands back the tiles already on screen along with
     * the new one. Uploading the lot meant deleting and re-sending nine 16MB
     * textures in a single frame, which is a freeze of the better part of a
     * second — and it happened every time the flight neared the edge of what
     * was loaded.
     */
    private fun uploadPending() {
        val meshes: List<TerrainScene.TileMesh>
        synchronized(this) {
            if (pending.isEmpty()) return
            meshes = ArrayList(pending)
            pending.clear()
            val wanted = HashSet<Long>()
            for (mesh in meshes) wanted.add(mesh.key)
            val gone = ArrayList<Tile>()
            for (t in tiles) if (!wanted.contains(t.key)) gone.add(t)
            for (t in gone) {
                if (t.textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(t.textureId), 0)
                GLES20.glDeleteBuffers(2, intArrayOf(t.vertexBuffer, t.indexBuffer), 0)
                tiles.remove(t)
            }
        }
        for (mesh in meshes) {
            var already = false
            synchronized(this) {
                for (t in tiles) if (t.key == mesh.key) already = true
            }
            if (already) continue
            var texture = 0
            val bitmap = mesh.texture
            if (bitmap != null && !bitmap.isRecycled) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                texture = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                // Zoomed out, a 2048px texture lands on a few hundred pixels of
                // screen, and without a chain of smaller copies every one of
                // them reads a different corner of it: slow, and it shimmers.
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
        // apart. Lift them with distance instead of by a fixed metre.
        val lift = groundLift()
        Matrix.setIdentityM(liftMatrix, 0)
        Matrix.translateM(liftMatrix, 0, 0f, lift / verticalScale, 0f)
        Matrix.multiplyMM(liftMvp, 0, mvp, 0, liftMatrix, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, liftMvp, 0)

        if (shadow != null && sCount > 1) {
            // its own width, rather than whatever the last pass left set: the
            // shadow changed thickness as other lines came and went
            GLES20.glLineWidth(2f)
            shadow.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, shadow)
            // the route colour darkened, rather than an anonymous black line
            GLES20.glUniform4f(uColor, trackColor[0] * 0.45f, trackColor[1] * 0.45f,
                trackColor[2] * 0.45f, 0.85f)
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, sCount)
        }
        if (drops != null && dCount > 2) {
            drops.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, drops)
            GLES20.glUniform4f(uColor, trackColor[0], trackColor[1], trackColor[2], 0.18f)
            // translucent, so what is behind it must still be drawn
            // no depth writing: the curtain is see through, so what is behind it
            // has to keep drawing, and it must not hide the flight above it
            GLES20.glDepthMask(false)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, dCount)
            GLES20.glDepthMask(true)
        }
        val marker: FloatBuffer?
        val mCount: Int
        synchronized(this) { marker = markerBuffer; mCount = markerCount }
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

        val ring: FloatBuffer?
        val rCount: Int
        synchronized(this) { ring = circleBuffer; rCount = circleCount }
        if (ring != null && rCount > 2) {
            GLES20.glUniformMatrix4fv(uMvp, 1, false, liftMvp, 0)
            ring.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, ring)
            GLES20.glUniform4f(uColor, 0.2f, 0.6f, 1f, 0.9f)
            GLES20.glLineWidth(3f)
            GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, rCount)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        }
        if (marker != null && mCount > 1) {
            marker.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, marker)
            GLES20.glUniform4f(uColor, 0.2f, 0.8f, 1f, 1f)
            GLES20.glLineWidth(6f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, mCount)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 1, 1)
        }
        if (track != null && tCount > 1) {
            track.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, track)
            GLES20.glUniform4f(uColor, trackColor[0], trackColor[1], trackColor[2], 1f)
            GLES20.glLineWidth(4f)
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, tCount)
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
        Matrix.scaleM(modelMatrix, 0, drawSize, drawSize / verticalScale, drawSize)
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
        val mine: FloatBuffer?
        synchronized(this) { mine = if (myVisible) arrowBuffer else null }
        if (mine == null || modelProgram == 0) return

        // eased towards the compass rather than snapped to it, so it turns
        // like the needle on the map instead of twitching
        var turn = myHeadingTarget - myHeading
        while (turn > 180f) turn -= 360f
        while (turn < -180f) turn += 360f
        myHeading = (myHeading + turn * 0.12f + 360f) % 360f

        // a fraction of the distance out, so it is the same size on screen
        // however far the camera is
        val size = Math.max(2f, distance * 0.014f)
        // Clearance in proportion, because the arrow lies flat: zoomed out it
        // is tens of metres across, and a fixed quarter of a metre left its
        // uphill half buried in any slope.
        val lift = groundLift() + size * 0.3f
        Matrix.setIdentityM(arrowMatrix, 0)
        Matrix.translateM(arrowMatrix, 0, myX, myY + lift / verticalScale, myZ)
        Matrix.rotateM(arrowMatrix, 0, -myHeading, 0f, 1f, 0f)
        Matrix.scaleM(arrowMatrix, 0, size, size / verticalScale, size)
        Matrix.multiplyMM(arrowMvp, 0, mvp, 0, arrowMatrix, 0)

        GLES20.glUseProgram(modelProgram)
        val aPosition = GLES20.glGetAttribLocation(modelProgram, "aPosition")
        val aCorner = GLES20.glGetAttribLocation(modelProgram, "aCorner")
        val aNormal = GLES20.glGetAttribLocation(modelProgram, "aNormal")
        val stride = MODEL_FLOATS * 4
        mine.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, stride, mine)
        GLES20.glEnableVertexAttribArray(aPosition)
        mine.position(3)
        GLES20.glVertexAttribPointer(aCorner, 3, GLES20.GL_FLOAT, false, stride, mine)
        GLES20.glEnableVertexAttribArray(aCorner)
        mine.position(6)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, stride, mine)
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
            GLES20.glGetUniformLocation(modelProgram, "uBase"), 0.42f, 0.76f, 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 18)

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
