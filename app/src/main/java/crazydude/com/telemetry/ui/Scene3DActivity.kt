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
    }

    private lateinit var surface: GLSurfaceView
    private lateinit var status: TextView
    private val renderer = TerrainRenderer()
    private val scene = TerrainScene()

    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var loader: Thread? = null

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

        val root = FrameLayout(this)
        root.addView(surface, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val statusParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        statusParams.gravity = Gravity.TOP or Gravity.START
        root.addView(status, statusParams)
        setContentView(root)

        val points = pending
        pending = null
        if (points == null || !scene.setTrack(points)) {
            status.text = "No flight to show"
            return
        }

        renderer.setTrack(scene.track, scene.shadow)
        renderer.target = floatArrayOf(0f, heightOfTrack() / 2f, 0f)
        renderer.distance = Math.max(600f, scene.extent * 3f)
        status.text = "Loading terrain…"

        // the network and the tile decoding are far too slow for the UI thread
        val worker = Thread(Runnable {
            scene.loadTerrain(points,
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
                status.text = if (scene.tiles.isEmpty()) {
                    "No terrain here — showing the flight alone"
                } else {
                    ""
                }
            }
        })
        worker.name = "terrain-load"
        loader = worker
        worker.start()
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
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> lastSpan = spanOf(event)
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val span = spanOf(event)
                    if (lastSpan > 0f && span > 0f) {
                        renderer.distance =
                            (renderer.distance * lastSpan / span).coerceIn(50f, 200000f)
                    }
                    lastSpan = span
                } else {
                    // a finger drags the world round, so the model stays put
                    renderer.azimuth -= (event.x - lastX) * 0.3f
                    renderer.elevation =
                        (renderer.elevation + (event.y - lastY) * 0.2f).coerceIn(3f, 87f)
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> lastSpan = 0f
        }
        return true
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
    }

    override fun onPause() {
        super.onPause()
        surface.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        loader?.interrupt()
    }
}
