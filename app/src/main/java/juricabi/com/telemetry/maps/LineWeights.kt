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
 * **The ground view is not measured in these.** It draws the same flight, the
 * same line home and the same heading line, and it has its own widths for them
 * — in `TerrainRenderer`, given to `glLineWidth`, which counts real pixels
 * rather than density-independent ones. Four here is roughly eleven there. The
 * two sets cannot simply be shared without deciding what a width means first,
 * so they are deliberately apart, and the ordering below is worth comparing
 * with the ground view's by hand: they do not currently agree about whether the
 * line home is heavier than the line ahead.
 */
object LineWeights {

    /** The flight itself, and the plan for it: the heaviest, and equal. */
    const val FLIGHT = 4f

    /** A plan is the same weight as the flight, so the two read as one kind. */
    const val PLAN = FLIGHT

    /** Back to whoever is holding the phone. */
    const val HOME = 2f

    /**
     * Where the model is pointing.
     *
     * The same weight as the line home as it happens, and its own number
     * rather than a reference to it: they are two different lines and either
     * may want changing without the other. It was three, which put the line
     * ahead halfway between the flight and the line home for no reason anybody
     * could give.
     */
    const val HEADING = 2f
}
