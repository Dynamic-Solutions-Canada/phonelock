package home.phonelock.parent

/**
 * Counts the foreground-service starts Android hasn't yet seen answered. Every startForegroundService() call
 * must be followed by the service calling startForeground(), or Android kills the app; stopping the service
 * with stopService() before that happens does exactly that. So while a start is unanswered, a stop has to go
 * through the service too (see [BackgroundConnection.stop]).
 */
internal class PendingStarts {
    private var pending = 0

    /** A startForegroundService() call succeeded. */
    @Synchronized
    fun requested() {
        pending++
    }

    /** The service called startForeground() for one start. A restart Android makes by itself counts for none. */
    @Synchronized
    fun answered() {
        if (pending > 0) pending--
    }

    /** Whether a stop must be sent through the service, because a start is still waiting for startForeground(). */
    @Synchronized
    fun stopNeedsService(): Boolean = pending > 0
}
