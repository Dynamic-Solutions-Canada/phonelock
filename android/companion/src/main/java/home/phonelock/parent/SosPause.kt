package home.phonelock.parent

/**
 * When a parent pauses the emergency alarm for one child's phone on this phone, and whether an alert arriving
 * now should ring. A paused alert still arrives, with a normal notification sound, her location and messages;
 * only the alarm (the looping sound that ignores silent mode) is held back. This exists because SOS can be
 * pressed again and again: the child's side never limits it, so a real emergency always gets through, and the
 * parent decides when an alarm has stopped meaning anything.
 *
 * Pure functions, so the times can be tested without a device.
 */
internal object SosPause {
    /** How long the notification's one-tap "Pause 1 h" lasts, in minutes. */
    const val QUICK_MINUTES = 60L

    /** The end (ms) of a pause of [minutes] started at [now] (ms); lengths come from LengthDialog. */
    fun forMinutes(now: Long, minutes: Long): Long = now + minutes * 60_000L

    /** Whether a pause ending at [until] (ms, 0 for none) still holds at [now]. */
    fun holds(until: Long, now: Long): Boolean = now < until
}
