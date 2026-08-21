package juricabi.com.telemetry.ui

/**
 * Whether a loaded replay starts, decided in one place.
 *
 * Three answers used to thread through the activity as fields: the play/pause
 * a rotation is resuming to, the autostart preference behind it, and the hold
 * that keeps playback off an empty world while the 3D ground is still on its
 * way. Every one of them produced a field bug before they were gathered —
 * a hold that outlived its replay started the next log by ground arriving
 * for something else.
 *
 * [shouldStart] is the single authority, consulted by the player's own
 * autostart ask; the verbs around it are the moments that change the answer.
 */
class ReplayHold(
    /** The standing preference a fresh open falls back to. */
    private val autostart: () -> Boolean
) {

    /**
     * The play/pause a rotation is resuming to, or null for a fresh open.
     * Read once, then cleared, so a later load falls back to the preference.
     */
    private var resumePlay: Boolean? = null

    private var waitingForGround = false

    /** A rotation resumes to this play/pause; a fresh open arms null. */
    fun armResume(playing: Boolean?) {
        resumePlay = playing
    }

    /** A hand on the play button: nothing is owed to the ground afterwards. */
    fun handTakesOver() {
        waitingForGround = false
    }

    /**
     * The replay this hold belonged to is going — a new one opening, or this
     * one closing. Left set, the hold outlived the replay that asked for it:
     * the next log to be opened was started by ground arriving for something
     * else.
     */
    fun clear() {
        waitingForGround = false
    }

    /**
     * The one place that decides whether a loaded replay starts. On a
     * rotation resume that is the play/pause the replay had; on a fresh open
     * it is the preference. Held only for ground already on its way
     * ([groundOnItsWay]) — a replay-bound view waits for the flight's first
     * fix before loading any ground, and that fix comes from playback, so
     * holding for ground not yet begun would deadlock the two.
     */
    fun shouldStart(groundOnItsWay: Boolean): Boolean {
        val wantPlay = resumePlay ?: autostart()
        resumePlay = null
        if (!wantPlay) return false
        if (groundOnItsWay) {
            waitingForGround = true
            return false
        }
        return true
    }

    /** A view swap stopped a running replay; the ground owes it a restart. */
    fun holdForGround() {
        waitingForGround = true
    }

    /** Ground arrived: whether a start is owed. Answered once. */
    fun releaseForGround(): Boolean {
        if (!waitingForGround) return false
        waitingForGround = false
        return true
    }
}
