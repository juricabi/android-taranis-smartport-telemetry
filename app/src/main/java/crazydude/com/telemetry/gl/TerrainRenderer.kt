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

    private var surfaceWidth = 1
    private var surfaceHeight = 1

    // ------------------------------------------------------------- feeding

    /** Called off the GL thread; uploaded on the next frame. */
    @Synchronized
    fun submit(meshes: List<TerrainScene.TileMesh>) {
        pending.clear()
        pending.addAll(meshes)
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
        // a new context throws away every texture and buffer we had
        synchronized(this) { tiles.clear() }
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
        val eyeX = target[0] + (distance * Math.cos(el) * Math.sin(az)).toFloat()
        val eyeY = target[1] + (distance * Math.sin(el)).toFloat()
        val eyeZ = target[2] + (distance * Math.cos(el) * Math.cos(az)).toFloat()
        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, target[0], target[1], target[2], 0f, 1f, 0f)

        // the exaggeration lives in the matrix, so nothing has to be rebuilt
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
        if (track != null && tCount > 1) {
            track.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, track)
            GLES20.glUniform4f(uColor, trackColor[0], trackColor[1], trackColor[2], 1f)
            GLES20.glLineWidth(4f)
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, tCount)
        }
        GLES20.glDisableVertexAttribArray(aPosition)
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
