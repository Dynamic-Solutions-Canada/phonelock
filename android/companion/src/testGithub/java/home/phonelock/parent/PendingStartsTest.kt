package home.phonelock.parent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A stop goes through the service exactly while a foreground start is still unanswered. */
class PendingStartsTest {
    @Test
    fun nothingPendingStopsDirectly() {
        assertFalse(PendingStarts().stopNeedsService())
    }

    @Test
    fun anUnansweredStartRoutesTheStopThroughTheService() {
        val s = PendingStarts()
        s.requested()
        assertTrue(s.stopNeedsService())
        s.answered()
        assertFalse(s.stopNeedsService())
    }

    @Test
    fun aStartThenAStopBothNeedAnswering() {
        val s = PendingStarts()
        s.requested() // start
        s.requested() // stop sent through the service while the start was pending
        s.answered()
        assertTrue(s.stopNeedsService())
        s.answered()
        assertFalse(s.stopNeedsService())
    }

    @Test
    fun aRestartAndroidMakesByItselfDoesNotGoNegative() {
        val s = PendingStarts()
        s.answered() // START_STICKY restart after the process died: no matching request
        assertFalse(s.stopNeedsService())
        s.requested()
        assertTrue(s.stopNeedsService())
    }
}
