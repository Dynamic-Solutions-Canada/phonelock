package home.phonelock.parent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A child asking again for the same app leaves parents one request to answer: the newest. */
class RequestTest {
    private fun ask(req: String, app: String = "com.whatsapp", ts: Long, device: String = "kid") = Request(device, req, app, "WhatsApp", 60, ts)

    @Test
    fun aNewerAskForTheSameAppReplacesTheOlder() {
        assertTrue(ask("b", ts = 2).replaces(ask("a", ts = 1)))
        assertFalse(ask("a", ts = 1).replaces(ask("b", ts = 2)))
    }

    @Test
    fun otherAppsAndOtherDevicesAreKept() {
        assertFalse(ask("b", ts = 2).replaces(ask("a", app = "com.youtube", ts = 1)))
        assertFalse(ask("b", ts = 2).replaces(ask("a", ts = 1, device = "other")))
    }

    @Test
    fun aRequestDoesNotReplaceItself() {
        assertFalse(ask("a", ts = 1).replaces(ask("a", ts = 1)))
    }

    @Test
    fun newestOnlyKeepsOnePerAppInOrder() {
        val list = listOf(ask("a", ts = 1), ask("y", app = "com.youtube", ts = 2), ask("b", ts = 3))
        assertEquals(listOf("y", "b"), Request.newestOnly(list).map { it.req })
    }
}
