package juricabi.com.telemetry.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resume/autostart/ground-hold matrix as plain assertions — the rules
 * that used to be testable only by rotating a phone mid-replay.
 */
class ReplayHoldTest {

    private var autostart = true
    private fun hold() = ReplayHold { autostart }

    @Test
    fun aFreshOpenFollowsThePreference() {
        autostart = true
        assertTrue(hold().shouldStart(groundOnItsWay = false))
        autostart = false
        assertFalse(hold().shouldStart(groundOnItsWay = false))
    }

    @Test
    fun aRotationResumeWinsOverThePreferenceOnce() {
        autostart = true
        val h = hold()
        h.armResume(false)
        // the replay was paused when the phone turned: it comes back paused
        assertFalse(h.shouldStart(groundOnItsWay = false))
        // consumed: the next load falls back to the preference
        assertTrue(h.shouldStart(groundOnItsWay = false))
    }

    @Test
    fun groundOnItsWayHoldsPlaybackAndOwesExactlyOneRelease() {
        val h = hold()
        assertFalse("held for the ground", h.shouldStart(groundOnItsWay = true))
        assertTrue("the ground arriving owes the start", h.releaseForGround())
        assertFalse("owed once, not on every tile", h.releaseForGround())
    }

    @Test
    fun aPausedResumeNeverArmsTheHold() {
        val h = hold()
        h.armResume(false)
        // not starting means not waiting: ground arriving later must not
        // start a replay that was resumed paused
        assertFalse(h.shouldStart(groundOnItsWay = true))
        assertFalse(h.releaseForGround())
    }

    @Test
    fun aHandOnThePlayButtonCancelsTheDebt() {
        val h = hold()
        h.shouldStart(groundOnItsWay = true)
        h.handTakesOver()
        assertFalse("the hand already answered", h.releaseForGround())
    }

    @Test
    fun aClosedReplayLeavesNoHoldBehind() {
        val h = hold()
        h.shouldStart(groundOnItsWay = true)
        h.clear()
        assertFalse("the next log must not be started by stray ground", h.releaseForGround())
    }

    @Test
    fun aViewSwapReinstatesTheDebt() {
        val h = hold()
        h.holdForGround()
        assertTrue(h.releaseForGround())
    }
}
