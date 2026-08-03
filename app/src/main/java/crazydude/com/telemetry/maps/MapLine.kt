package crazydude.com.telemetry.maps

import crazydude.com.telemetry.utils.GeoUtils

abstract class MapLine {
    abstract fun remove()
    abstract fun addPoints(points: List<Position>)
    abstract fun setPoint(index: Int, position: Position)
    abstract fun clear()
    abstract fun removeAt(index: Int)

    abstract val size: Int
    abstract var color: Int

    private var lastLat = 0.0
    private var lastLon = 0.0

    var spoints: MutableList<Position> = mutableListOf()

    fun submitPoints(points: List<Position>) {
        spoints.addAll(points)
    }

    private fun simplifySPoints(limit: Int) {
        if (size == 0) {
            lastLat = 0.0
            lastLon = 0.0
        }

        // How far the model must move before the track gains a point. One
        // metre keeps the shape of a turn; five, as it was, cut the corners off
        // everything and made a circuit look like a polygon.
        //
        // It widens as the track gets long, so a whole flight still costs a
        // bounded number of points. The tests were the wrong way round before:
        // anything over 1500 matched the first rung, so it never reached the
        // others and every long track sat at ten metres.
        val points = size + spoints.size
        var threshold = 1
        if (limit > 1500) {
            threshold = when {
                points > 7000 -> 30
                points > 5000 -> 20
                points > 3000 -> 10
                points > 1500 -> 5
                else -> 1
            }
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

    fun commitPoints(limit: Int) {
        simplifySPoints(limit)
        var toRemove = (size + spoints.size) - limit
        if (toRemove >= size) {
            var fi = spoints.size - limit
            if (fi < 0) fi = 0
            val subList = spoints.subList(fi, spoints.size).toList()
            clear()
            addPoints(subList)
        } else {
            for (i in 1..toRemove) {
                removeAt(0)
            }
            addPoints(spoints)
            spoints.clear()
        }
    }
}
