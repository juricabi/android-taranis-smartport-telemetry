package juricabi.com.telemetry.maps

import juricabi.com.telemetry.utils.GeoUtils

abstract class MapLine {

    /** How heavy the line is drawn, in pixels. */
    abstract var width: Float

    abstract fun remove()
    abstract fun addPoints(points: List<Position>)
    abstract fun setPoint(index: Int, position: Position)
    abstract fun clear()

    abstract val size: Int
    abstract var color: Int

    private var lastLat = 0.0
    private var lastLon = 0.0

    var spoints: MutableList<Position> = mutableListOf()

    fun submitPoints(points: List<Position>) {
        spoints.addAll(points)
    }

    private fun simplifySPoints() {
        if (size == 0) {
            lastLat = 0.0
            lastLon = 0.0
        }

        // How far the model must move before the track gains a point. Two
        // metres keeps the shape of a turn while staying above the noise in a
        // fix, so a model sitting on the ground does not draw a cloud where it
        // landed; five, as it was, cut the corners off everything.
        //
        // It widens as the track gets long, so a whole flight still costs a
        // bounded number of points. The tests were the wrong way round before:
        // anything over 1500 matched the first rung, so it never reached the
        // others and every long track sat at ten metres.
        // Two metres for the first five thousand points — ten kilometres of
        // flying — and widening slowly after that.
        //
        // It used to start widening at fifteen hundred, which is three
        // kilometres, so an ordinary flight was already being coarsened. Each
        // point costs about four tenths of a microsecond to draw, measured, so
        // five thousand of them is a couple of milliseconds a frame and even a
        // hundred kilometre flight lands around thirteen thousand.
        val points = size + spoints.size
        val threshold = when {
            points > 15000 -> 30
            points > 10000 -> 20
            points > 8000 -> 10
            points > 5000 -> 5
            else -> 2
        }

        spoints = spoints.filter { i ->
            val d = GeoUtils.computeDistanceBetween(lastLat, lastLon, i.lat, i.lon)
            if (d >= threshold) {
                lastLat = i.lat
                lastLon = i.lon
                true
            } else {
                false
            }
        }.toMutableList()
    }

    /**
     * Hand the staged points to the line. Nothing is ever taken off it.
     *
     * There was a cap, and it dropped the oldest points first — so the start of
     * a flight quietly disappeared as it went on. It bought nothing: measured
     * on a 120Hz phone, a line of twenty five hundred points and one of a
     * hundred and fifty drew within a millisecond of each other. The length is
     * bounded by the thinning above anyway, which spreads points further apart
     * the longer the flight runs, so even a long one costs a few thousand.
     */
    fun commitPoints() {
        simplifySPoints()
        addPoints(spoints)
        spoints.clear()
    }
}
