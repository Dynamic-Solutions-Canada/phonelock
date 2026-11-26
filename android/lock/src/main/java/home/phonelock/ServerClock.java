package home.phonelock;

import android.content.Context;

/**
 * The hub's clock, as seen from this phone: the difference between the two, learned each time the hub accepts
 * a message (ntfy answers with the message's time). The family chat stamps and expires its messages by this
 * clock, so a phone whose own clock is wrong still shows the same times as everyone else and doesn't delete
 * messages early. Until the hub has answered once, it is the phone's own clock.
 */
final class ServerClock {
    private ServerClock() {}

    private static final String KEY_OFFSET = "server_clock_offset";

    /** The offset kept in memory once read or learned (ms to add to the phone's clock); null until then. */
    private static volatile Long offset;

    /** The hub's time now (ms), or the phone's own clock until the hub has answered once. */
    static long now(Context c) {
        return System.currentTimeMillis() + offset(c);
    }

    /**
     * Learns the offset from one message the hub accepted: it was sent between {@code sentAt} and
     * {@code answeredAt} (this phone's clock, ms), and the hub says it arrived at {@code serverSeconds} (ntfy
     * gives whole seconds).
     */
    static void note(Context c, long sentAt, long answeredAt, long serverSeconds) {
        Long learned = offsetFrom(sentAt, answeredAt, serverSeconds);
        if (learned == null) return;
        offset = learned;
        Policy.prefs(c).edit().putLong(KEY_OFFSET, learned).apply();
    }

    private static long offset(Context c) {
        Long o = offset;
        if (o == null) {
            o = Policy.prefs(c).getLong(KEY_OFFSET, 0);
            offset = o;
        }
        return o;
    }

    /**
     * The offset (ms to add to the phone's clock) from one exchange, or null when it can't be trusted: no server
     * time, or an answer so slow that the moment the hub stamped it is too uncertain. The hub's second is taken
     * at its middle, against the middle of the exchange.
     */
    static Long offsetFrom(long sentAt, long answeredAt, long serverSeconds) {
        if (serverSeconds <= 0 || answeredAt < sentAt || answeredAt - sentAt > 10_000L) return null;
        long server = serverSeconds * 1000 + 500;
        return server - (sentAt + (answeredAt - sentAt) / 2);
    }
}
