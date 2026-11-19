package home.phonelock.parent

import android.content.Context

/**
 * Play build: no background connection. Play restricts the foreground service and the battery-saving
 * exemption it needs, so messages arrive only by push through the build's PhoneLock hub (DIST-7). Phones on
 * any other server are refused when added, and a phone push doesn't reach yet shows a warning.
 */
object BackgroundConnection {
    /** This build keeps no connection in the background, and asks for no battery-saving exemption. */
    const val SUPPORTED = false

    fun start(c: Context) {}

    fun stop(c: Context) {}

    fun restart(c: Context) {}

    /** Whether a phone on [server] can be added: only on this build's hub, the one push reaches. */
    fun accepts(server: String) = acceptsServer(server, Push.hub)

    /** Whether messages from [d] arrive while the app is closed: only once push reaches it. */
    fun reaches(d: Device) = Push.covers(d)

    /**
     * The rule behind [accepts]: [server] is [hub], ignoring a trailing slash. A build without a hub (a
     * debug build without the Firebase settings) accepts nothing, because nothing could reach it.
     */
    internal fun acceptsServer(server: String, hub: String): Boolean {
        val wanted = hub.trimEnd('/')
        return wanted.isNotEmpty() && server.trimEnd('/') == wanted
    }
}
