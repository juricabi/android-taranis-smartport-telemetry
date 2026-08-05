package juricabi.com.telemetry.maps

abstract class MapLine {

    /** How heavy the line is drawn. See [LineWeights]. */
    abstract var width: Float

    abstract fun remove()
    abstract fun addPoints(points: List<Position>)
    abstract fun setPoint(index: Int, position: Position)

    /** Replace a moving line's whole shape as one renderer update. */
    open fun setPoints(points: List<Position>) {
        clear()
        addPoints(points)
    }

    abstract fun clear()

    abstract val size: Int
    abstract var color: Int

    /** Points handed over but not yet on the line. */
    var spoints: MutableList<Position> = mutableListOf()

    fun submitPoints(points: List<Position>) {
        spoints.addAll(points)
    }

    /**
     * Hand the staged points to the line. Every one of them, and nothing is
     * ever taken off.
     *
     * The track used to be thinned on the way in — a point only kept once the
     * model had moved two metres from the last one, widening to thirty as the
     * flight got long, so a hundred kilometre flight came down to about
     * thirteen thousand points. That was not for the look of it. The map
     * reprojected every point of every line on the UI thread on every frame,
     * and each one cost about four tenths of a microsecond, so a few thousand
     * of them was already milliseconds a frame before anything else was drawn.
     * Thinning bought the frame rate and paid for it in corners: it is a
     * straight line between the points that survive, and the ones dropped are
     * the ones nearest together, which is exactly where a turn is tightest.
     *
     * The map is drawn by the GPU now. The points are handed over once and live
     * in the renderer's own buffers; what it costs to draw them stopped
     * depending on how many there are. So there is nothing left to buy and the
     * flight is drawn from every fix that was recorded — which is what the
     * altitude profile and the ground view have always used, and the three
     * finally agree about the shape of a turn.
     */
    fun commitPoints() {
        addPoints(spoints)
        spoints.clear()
    }
}
