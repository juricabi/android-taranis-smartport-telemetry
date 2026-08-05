package juricabi.com.telemetry.maps

/**
 * How heavily each line of a flight is drawn on the map.
 *
 * In one place because they are only meaningful against each other: what
 * matters is that the flight is the heaviest thing on the map and the lines
 * explaining it are lighter, and that cannot be read off four numbers written
 * four screens apart. The flight line used not to give a width at all and took
 * whatever the map it landed on defaulted to, which was not even the same on
 * the two maps.
 *
 * These are density-independent, as every map line is: both map wrappers are
 * handed the number and each scales it for the screen its own way.
 *
 * **The ground view is not measured in these**, but it now agrees with them.
 * It draws the same flight, the same line home and the same line ahead, and it
 * has its own widths in `TerrainRenderer` — given to `glLineWidth`, which
 * counts real pixels rather than density-independent ones, so four here is
 * roughly eleven there. The numbers cannot be shared without first deciding
 * what a width means, but the order can be, and is: flight heaviest, then the
 * way home, then where the nose is pointing. Four, three, two in both places.
 * Changing one of them here without looking at the other is how that stops
 * being true.
 */
object LineWeights {

    /** The flight itself, and the plan for it: the heaviest, and equal. */
    const val FLIGHT = 4f

    /** A plan is the same weight as the flight, so the two read as one kind. */
    const val PLAN = FLIGHT

    /**
     * Back to whoever is holding the phone.
     *
     * Between the flight and the line ahead. It matters more than where the
     * model is pointing — it is the way back — and less than the flight
     * itself.
     */
    const val HOME = 3f

    /** Where the model is pointing: the lightest line on the map. */
    const val HEADING = 2f
}
