package home.phonelock.parent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Play build keeps no background connection and accepts phones only on its own hub (DIST-7). */
class BackgroundConnectionTest {
    private val hub = "https://phonelock.example.com"

    @Test
    fun playBuildHasNoBackgroundConnection() {
        assertFalse(BackgroundConnection.SUPPORTED)
    }

    @Test
    fun acceptsOnlyTheBuildsHub() {
        assertTrue(BackgroundConnection.acceptsServer(hub, hub))
        assertTrue(BackgroundConnection.acceptsServer("$hub/", hub))
        assertTrue(BackgroundConnection.acceptsServer(hub, "$hub/"))
        assertFalse(BackgroundConnection.acceptsServer("https://ntfy.sh", hub))
        assertFalse(BackgroundConnection.acceptsServer("https://phonelock.example.com.evil.test", hub))
        assertFalse(BackgroundConnection.acceptsServer("http://phonelock.example.com", hub))
    }

    @Test
    fun buildWithoutAHubAcceptsNothing() {
        assertFalse(BackgroundConnection.acceptsServer(hub, ""))
        assertFalse(BackgroundConnection.acceptsServer("", ""))
    }
}
