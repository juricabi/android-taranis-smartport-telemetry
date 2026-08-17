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

    @Volatile
    private var epoch = 0L

    /** The flight a scene or an asynchronous terrain request belongs to. */
    fun currentEpoch(): Long = epoch

    /**
     * Publish one answer for one flight, and return the answer that won.
     *
     * A retired terrain loader may finish after another flight has started;
     * its epoch no longer matches and it is not allowed to overwrite the new
     * flight. Within one flight the first datum is kept, with the same one-way
     * correction TerrainScene permits: sea-level may become above-launch when
     * more of the flight proves it, but a proven above-launch frame never
     * flips back.
     */
    @Synchronized
    fun settle(
        reference: TerrainScene.Companion.Reference,
        forEpoch: Long
    ): TerrainScene.Companion.Reference? {
        if (forEpoch != epoch) return null
        settled?.let {
            if (!it.aboveLaunch && reference.aboveLaunch) {
                settled = reference
                return reference
            }
            // Within above-launch the lift may only grow. The first answer
            // is only what the flight had proven by then — right for one
            // that began on its launch, a lower bound for one that began
            // mid-air, a reconnect to a model already flying whose zero is
            // a launch this flight never saw. A later, tighter pass over
            // higher ground proves the heights sat lower than first
            // thought; proof never unproves.
            if (it.aboveLaunch && reference.aboveLaunch && reference.lift > it.lift) {
                settled = reference
                return reference
            }
            return it
        }
        settled = reference
        return reference
    }

    /** How much to add to this flight's reported heights, if it has settled. */
    @Synchronized
    fun lift(forEpoch: Long): Float? = if (forEpoch == epoch) settled?.lift else null

    /** Whether this flight's heights are proven measured from the launch. */
    @Synchronized
    fun aboveLaunch(forEpoch: Long): Boolean =
        forEpoch == epoch && settled?.aboveLaunch == true

    // A dead flight's answer deliberately does not survive it — not even
    // for a reconnect to the same aircraft moments later. A disconnect
    // asked for by hand is the person drawing a line, and state that
    // crosses such lines is where this app's bugs have lived. The next
    // flight proves its own ground; mid-air that answer starts as a bound
    // and ratchets toward the truth, which is the honest price.
    @Synchronized
    fun forget() {
        settled = null
        epoch++
    }
}
