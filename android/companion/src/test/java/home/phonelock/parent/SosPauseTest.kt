package home.phonelock.parent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a paused emergency alarm ends, and whether it still holds. */
class SosPauseTest {
    @Test
    fun lengthIsCountedInMinutes() {
        assertEquals(1_000L + 20 * 60_000L, SosPause.forMinutes(1_000L, 20))
        assertEquals(5 * 3600_000L, SosPause.forMinutes(0, 5 * 60))
    }

    @Test
    fun quickPauseIsAnHour() {
        assertEquals(3600_000L, SosPause.forMinutes(0, SosPause.QUICK_MINUTES))
    }

    @Test
    fun holdsUntilItsEnd() {
        assertTrue(SosPause.holds(until = 1000, now = 999))
        assertFalse(SosPause.holds(until = 1000, now = 1000))
        assertFalse(SosPause.holds(until = 0, now = 5))
    }
}
