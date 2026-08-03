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
                vShade = 0.65 + 0.35 * max(dot(normalize(aNormal), light), 0.0);
            }
        """

        private const val TERRAIN_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uHasTexture;
            varying vec2 vTexture;
            varying float vShade;
            void main() {
                vec3 base = mix(vec3(0.45, 0.44, 0.40),
                                texture2D(uTexture, vTexture).rgb, uHasTexture);
                gl_FragColor = vec4(base * vShade, 1.0);
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

        private const val FLOATS_PER_VERTEX = 8
    }

    private class Tile(
        val vertices: FloatBuffer,
        val indices: ShortBuffer,
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
    private var lineProgram = 0

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    /** Orbit: degrees round, degrees up, and how far out. */
    @Volatile var azimuth = 30f
    @Volatile var elevation = 28f
    @Volatile var distance = 1500f
    @Volatile var target = floatArrayOf(0f, 0f, 0f)

    /** Height is exaggerated a little, or a hill reads as flat from above. */
    @Volatile var verticalScale = 1.4f

    @Volatile var trackColor = floatArrayOf(1f, 0.85f, 0.1f, 1f)

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
        if (modelBuffer == null) modelBuffer = floats(dart())
        modelCount = 18
    }

    /** A dart: nose forward along -z, two wings and a fin, eighteen vertices. */
    private fun dart(): FloatArray {
        val nose = floatArrayOf(0f, 0f, -1f)
        val left = floatArrayOf(-0.6f, 0f, 0.7f)
        val right = floatArrayOf(0.6f, 0f, 0.7f)
        val top = floatArrayOf(0f, 0.4f, 0.5f)
        val tail = floatArrayOf(0f, 0f, 0.35f)
        val faces = arrayOf(
            nose, left, top,
            nose, top, right,
            nose, right, tail,
            nose, tail, left,
            left, right, top,
            left, tail, right
        )
        val out = FloatArray(faces.size * 3)
        var i = 0
        for (f in faces) {
            out[i++] = f[0]; out[i++] = f[1]; out[i++] = f[2]
        }
        return out
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
    @Volatile private var myHeading = 0f

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
        myHeading = headingDegrees
        myVisible = true
        if (arrowBuffer == null) arrowBuffer = floats(arrowMesh())
    }

    /** A unit arrow: nose along -z, swept corners, a raised spine. */
    private fun arrowMesh(): FloatArray {
        val nose = floatArrayOf(0f, 0f, -1.6f)
        val left = floatArrayOf(-1f, 0f, 1f)
        val right = floatArrayOf(1f, 0f, 1f)
        val tail = floatArrayOf(0f, 0f, 0.4f)
        val spine = floatArrayOf(0f, 0.8f, 0.1f)
        val faces = arrayOf(
            nose, left, spine,
            nose, spine, right,
            left, tail, spine,
            tail, right, spine,
            nose, tail, left,
            nose, right, tail
        )
        val out = FloatArray(faces.size * 3)
        var i = 0
        for (f in faces) {
            out[i++] = f[0]; out[i++] = f[1]; out[i++] = f[2]
        }
        return out
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

        // a rung between the flight and its shadow every so often, which is what
        // makes height readable on a flat screen
        if (track.size == shadow.size && trackCount > 1) {
            val step = Math.max(1, trackCount / 60)
            val rungs = ArrayList<Float>()
            var i = 0
            while (i < trackCount) {
                rungs.add(track[i * 3]); rungs.add(track[i * 3 + 1]); rungs.add(track[i * 3 + 2])
                rungs.add(shadow[i * 3]); rungs.add(shadow[i * 3 + 1]); rungs.add(shadow[i * 3 + 2])
                i += step
            }
            val array = FloatArray(rungs.size)
            for (j in rungs.indices) array[j] = rungs[j]
            dropBuffer = floats(array)
            dropCount = array.size / 3
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
        val ratio = surfaceWidth.toFloat() / surfaceHeight
        Matrix.perspectiveM(projection, 0, 50f, ratio, 5f, 120000f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        uploadPending()

        val az = Math.toRadians(azimuth.toDouble())
        val el = Math.toRadians(elevation.toDouble().coerceIn(3.0, 87.0))
        // The ground is drawn stretched by verticalScale, so the camera has to
        // live in that same stretched space. Placing it in unstretched metres
        // put it below hills it was supposed to clear — which is why it could
        // still end up inside them.
        val targetFloorRaw = groundUnderCamera?.invoke(target[0], target[2])
        val targetYRaw = if (targetFloorRaw != null && target[1] < targetFloorRaw + 5f) {
            targetFloorRaw + 5f
        } else {
            target[1]
        }
        val targetY = targetYRaw * verticalScale

        val eyeX = target[0] + (distance * Math.cos(el) * Math.sin(az)).toFloat()
        var eyeY = targetY + (distance * Math.sin(el)).toFloat()
        val eyeZ = target[2] + (distance * Math.cos(el) * Math.cos(az)).toFloat()
        // never under the ground: it is opaque from below and the view becomes
        // a meaningless slab
        val floorY = groundUnderCamera?.invoke(eyeX, eyeZ)
        if (floorY != null && eyeY < floorY * verticalScale + 40f) {
            eyeY = floorY * verticalScale + 40f
        }
        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, target[0], targetY, target[2], 0f, 1f, 0f)

        // the exaggeration lives in the matrix, so nothing has to be rebuilt,
        // and the camera above was placed in the same stretched space
        val scaled = FloatArray(16)
        Matrix.setIdentityM(scaled, 0)
        Matrix.scaleM(scaled, 0, 1f, verticalScale, 1f)
        val vm = FloatArray(16)
        Matrix.multiplyMM(vm, 0, view, 0, scaled, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, vm, 0)

        drawTerrain()
        drawLines()
    }

    private fun uploadPending() {
        val meshes: List<TerrainScene.TileMesh>
        synchronized(this) {
            if (pending.isEmpty()) return
            meshes = ArrayList(pending)
            pending.clear()
            for (t in tiles) if (t.textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(t.textureId), 0)
            }
            tiles.clear()
        }
        for (mesh in meshes) {
            var texture = 0
            val bitmap = mesh.texture
            if (bitmap != null && !bitmap.isRecycled) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                texture = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            }
            synchronized(this) {
                tiles.add(Tile(floats(mesh.vertices), shorts(mesh.indices),
                    mesh.indices.size, texture))
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
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)

        val snapshot: List<Tile>
        synchronized(this) { snapshot = ArrayList(tiles) }
        for (tile in snapshot) {
            val stride = FLOATS_PER_VERTEX * 4
            tile.vertices.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, stride, tile.vertices)
            GLES20.glEnableVertexAttribArray(aPosition)
            tile.vertices.position(3)
            GLES20.glVertexAttribPointer(aTexture, 2, GLES20.GL_FLOAT, false, stride, tile.vertices)
            GLES20.glEnableVertexAttribArray(aTexture)
            tile.vertices.position(5)
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, stride, tile.vertices)
            GLES20.glEnableVertexAttribArray(aNormal)

            if (tile.textureId != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tile.textureId)
                GLES20.glUniform1f(uHasTexture, 1f)
            } else {
                GLES20.glUniform1f(uHasTexture, 0f)
            }
            tile.indices.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, tile.count,
                GLES20.GL_UNSIGNED_SHORT, tile.indices)

            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexture)
            GLES20.glDisableVertexAttribArray(aNormal)
        }
    }

    private fun drawLines() {
        if (lineProgram == 0) return
        GLES20.glUseProgram(lineProgram)
        val aPosition = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        val uMvp = GLES20.glGetUniformLocation(lineProgram, "uMvp")
        val uColor = GLES20.glGetUniformLocation(lineProgram, "uColor")
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glEnableVertexAttribArray(aPosition)

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

        if (shadow != null && sCount > 1) {
            shadow.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, shadow)
            GLES20.glUniform4f(uColor, 0f, 0f, 0f, 0.5f)
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, sCount)
        }
        if (drops != null && dCount > 1) {
            drops.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, drops)
            GLES20.glUniform4f(uColor, 1f, 1f, 1f, 0.25f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, dCount)
        }
        drawModel(aPosition, uMvp, uColor)

        val marker: FloatBuffer?
        val mCount: Int
        synchronized(this) { marker = markerBuffer; mCount = markerCount }
        val mine: FloatBuffer?
        synchronized(this) { mine = if (myVisible) arrowBuffer else null }
        if (mine != null) {
            // a fortieth of the distance out, so it is the same size on screen
            // however far the camera is
            val size = Math.max(3f, distance * 0.025f)
            Matrix.setIdentityM(arrowMatrix, 0)
            Matrix.translateM(arrowMatrix, 0, myX, myY, myZ)
            Matrix.rotateM(arrowMatrix, 0, -myHeading, 0f, 1f, 0f)
            Matrix.scaleM(arrowMatrix, 0, size, size / verticalScale, size)
            Matrix.multiplyMM(arrowMvp, 0, mvp, 0, arrowMatrix, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, arrowMvp, 0)
            mine.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, mine)
            GLES20.glUniform4f(uColor, 0.15f, 0.55f, 1f, 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 18)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        }

        val ring: FloatBuffer?
        val rCount: Int
        synchronized(this) { ring = circleBuffer; rCount = circleCount }
        if (ring != null && rCount > 2) {
            ring.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, ring)
            GLES20.glUniform4f(uColor, 0.2f, 0.6f, 1f, 0.9f)
            GLES20.glLineWidth(3f)
            GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, rCount)
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
    }

    private fun drawModel(aPosition: Int, uMvp: Int, uColor: Int) {
        val buffer: FloatBuffer?
        synchronized(this) { buffer = if (modelVisible) modelBuffer else null }
        if (buffer == null || modelCount < 3) return

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, modelX, modelY, modelZ)
        // yaw, then pitch, then roll — the order an aircraft's attitude is
        // built in, so a banked turn looks like a banked turn
        Matrix.rotateM(modelMatrix, 0, -modelHeading, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, modelPitch, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, -modelRoll, 0f, 0f, 1f)
        // undo the vertical exaggeration on the dart itself, or it grows a
        // taller fin the more the ground is stretched
        // held at a size on screen rather than in metres, as the arrow is
        val drawSize = Math.max(modelSize, distance * 0.02f)
        Matrix.scaleM(modelMatrix, 0, drawSize, drawSize / verticalScale, drawSize)
        Matrix.multiplyMM(modelMvp, 0, mvp, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, modelMvp, 0)
        buffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, buffer)
        GLES20.glUniform4f(uColor, 1f, 0.25f, 0.15f, 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, modelCount)
        // back to the plain matrix for the lines that follow
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
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
