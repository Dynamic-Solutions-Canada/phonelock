package home.phonelock.parent

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * GitHub build: the live connection ([ParentService]) for paired phones that push doesn't reach, such as
 * phones on ntfy.sh or on a hub without Firebase. Any server is accepted, because the connection covers it.
 * The Play build's version has no connection and accepts only the build's hub (DIST-7).
 */
object BackgroundConnection {
    /** This build can keep a connection in the background (and asks to be exempt from battery saving). */
    const val SUPPORTED = true

    /** Starts, or re-subscribes, the connection; stops it instead when every phone is reached by push. */
    fun start(c: Context) {
        // No permanent notification when every phone is reached by push.
        if (Hub.devices.value.none { !Push.covers(it) }) {
            stop(c)
            return
        }
        runCatching { c.startForegroundService(Intent(c, ParentService::class.java).setAction("restart")) }
            .onFailure { Log.w(TAG, "start: $it") }
    }

    fun stop(c: Context) {
        c.stopService(Intent(c, ParentService::class.java))
    }

    fun restart(c: Context) = start(c)

    /** Whether a phone on [server] can be added: always, since the connection reaches any server. */
    fun accepts(server: String) = true

    /** Whether messages from [d] arrive while the app is closed: always, by push or by the connection. */
    fun reaches(d: Device) = true
}
