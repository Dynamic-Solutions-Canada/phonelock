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

    /** The service action that shows the notification, as every start must, then stops the service. */
    internal const val ACTION_STOP = "stop"

    /** Starts the service has yet to answer with startForeground(); see [stop]. */
    internal val starts = PendingStarts()

    /** Starts, or re-subscribes, the connection; stops it instead when every phone is reached by push. */
    fun start(c: Context) {
        // No permanent notification when every phone is reached by push.
        if (Hub.devices.value.none { !Push.covers(it) }) {
            stop(c)
            return
        }
        runCatching {
            c.startForegroundService(Intent(c, ParentService::class.java).setAction("restart"))
            starts.requested()
        }.onFailure { Log.w(TAG, "start: $it") }
    }

    /**
     * Stops the connection. While a start is still waiting for the service to call startForeground(), stopping
     * it outright would make Android kill the app, so the stop is sent through the service instead: it answers
     * the start, then stops itself.
     */
    fun stop(c: Context) {
        if (!starts.stopNeedsService()) {
            c.stopService(Intent(c, ParentService::class.java))
            return
        }
        runCatching {
            c.startForegroundService(Intent(c, ParentService::class.java).setAction(ACTION_STOP))
            starts.requested()
        }.onFailure { Log.w(TAG, "stop: $it") }
    }

    fun restart(c: Context) = start(c)

    /** Whether a phone on [server] can be added: always, since the connection reaches any server. */
    fun accepts(server: String) = true

    /** Whether messages from [d] arrive while the app is closed: always, by push or by the connection. */
    fun reaches(d: Device) = true
}
