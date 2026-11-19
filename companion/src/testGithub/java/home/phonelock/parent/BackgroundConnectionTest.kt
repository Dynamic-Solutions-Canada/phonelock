package home.phonelock.parent

import org.junit.Assert.assertTrue
import org.junit.Test

/** The GitHub build's background connection reaches any server, so every phone can be added. */
class BackgroundConnectionTest {
    @Test
    fun githubBuildHasABackgroundConnection() {
        assertTrue(BackgroundConnection.SUPPORTED)
    }

    @Test
    fun acceptsAnyServer() {
        assertTrue(BackgroundConnection.accepts("https://ntfy.sh"))
        assertTrue(BackgroundConnection.accepts("https://hub.example.com/"))
    }
}
