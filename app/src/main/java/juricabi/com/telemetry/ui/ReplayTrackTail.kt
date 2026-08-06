package juricabi.com.telemetry.ui

import juricabi.com.telemetry.maps.Position

/** Maximum exact-route overlap kept in the immediate 2D flight-head layer. */
internal const val REPLAY_TRACK_HEAD_POINTS = 96

/**
 * The route tail ending at a paused replay's seek target.
 *
 * It is deliberately derived only from the published route. A previous
 * presentation head belongs to the old bar position and joining it to [target]
 * would draw a shortcut that is not part of the recorded flight.
 */
internal fun replayTrackTail(
    publishedRoute: List<Position>,
    target: Position,
    limit: Int = REPLAY_TRACK_HEAD_POINTS
): List<Position> {
    require(limit >= 2)
    val targetAlreadyLast = publishedRoute.lastOrNull() == target
    val routeSlots = if (targetAlreadyLast) limit else limit - 1
    val from = Math.max(0, publishedRoute.size - routeSlots)
    val tail = ArrayList<Position>(Math.min(limit, publishedRoute.size + 1))
    for (i in from until publishedRoute.size) tail.add(publishedRoute[i])
    if (!targetAlreadyLast) tail.add(target)
    return tail
}
