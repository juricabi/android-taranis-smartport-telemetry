package juricabi.com.telemetry.ui

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The queueing that broke in the field: a request fired under a standing
 * dialog is cancelled unseen, so asks must take turns.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionFunnelTest {

    private val asked = mutableListOf<Int>()

    private fun funnel(): PermissionFunnel {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        return PermissionFunnel(activity, showDialog = {}) { _, code -> asked.add(code) }
    }

    @Test
    fun asksTakeTurnsAndKeepTheirOrder() {
        val f = funnel()
        f.ask("a", 1)
        f.ask("b", 2)
        f.ask("c", 3)
        assertEquals("one dialog at a time", listOf(1), asked)
        f.resolved()
        assertEquals(listOf(1, 2), asked)
        f.resolved()
        assertEquals(listOf(1, 2, 3), asked)
        f.resolved()
        assertEquals("nothing owed once the queue is dry", listOf(1, 2, 3), asked)
    }

    @Test
    fun aRepeatOfAStandingOrWaitingAskIsDropped() {
        val f = funnel()
        f.ask("a", 1)
        f.ask("a", 1)
        f.ask("b", 2)
        f.ask("b", 2)
        f.resolved()
        f.resolved()
        assertEquals(listOf(1, 2), asked)
    }
}
