package juricabi.com.telemetry.gl

/**
 * What the reported heights of the flight being watched mean, settled once.
 *
 * A quad reports height above where it armed and a GPS reports height above the
 * sea; which of the two is being read is worked out from the flight against the
 * ground beneath it. Both the 3D view and the altitude profile need the answer,
 * and both can work it out — but from terrain sampled at different resolutions,
 * the ground view finely over a window around the model and the profile coarsely
 * over the whole flight. Two answers to one question is the same flight drawn at
 * two heights, metres apart on a steep launch site.
 *
 * So whichever settles it first says so here, and the other asks rather than
 * works it out again. Forgotten when a flight ends, because the next one is a
 * new question.
 */
object AltitudeFrame {

    @Volatile
    private var settled: TerrainScene.Companion.Reference? = null

    fun remember(reference: TerrainScene.Companion.Reference) {
        settled = reference
    }

    /** How much to add to reported heights, or null if nothing has settled it. */
    fun lift(): Float? = settled?.lift

    fun forget() {
        settled = null
    }
}
