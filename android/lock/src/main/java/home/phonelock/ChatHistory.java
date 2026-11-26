package home.phonelock;

import java.util.ArrayList;
import java.util.List;

/**
 * The family chat's thread and rules, in plain Java so they can be unit tested without a device: storing a
 * message once per {@code cid} with the next {@code seq}, keeping 90 days and at most 2,000 messages, paging
 * history for parents, and the parents' controls on when the child may send (slow mode and timeout).
 * {@link ChatLog} keeps one of these on disk and does the messaging. See docs/chat.md.
 *
 * <p>Not thread safe: {@link ChatLog} holds its lock around every use.
 */
final class ChatHistory {
    /** Messages older than this (by the phone's clock when it stored them) are dropped. */
    static final long KEEP_MS = 90L * 24 * 3600_000L;
    /** At most this many messages are kept; the oldest go first. */
    static final int MAX_MESSAGES = 2000;
    /** Messages per {@code chatlog} page. */
    static final int PAGE = 200;
    /** Longest message, in characters (code points, so an emoji counts once). */
    static final int MAX_TEXT = 1000;
    /** Slow mode's choices, in minutes between the child's messages; 0 is off. */
    static final int[] EVERY_CHOICES = {0, 1, 5, 15};
    /** The child's "seen" mark goes to the parents at most this often. */
    static final long READ_EVERY_MS = 60_000L;

    /** The child's phone, as a message's sender (the same as {@link Remote#FROM}). */
    static final String PHONE = "phone";

    /** One stored message. */
    static final class Msg {
        /** Its place in the thread, counting up from 1; never reused. */
        final long seq;
        /** The sender's random id for it, the same on every resend. */
        final String cid;
        /** {@link #PHONE} for the child, else the parent's name as they sent it. */
        final String from;

        final String text;
        /** When the sender wrote it, by the sender's clock (shown, never used for order). */
        final long at;
        /** When this phone stored it, by its own clock: what the 90-day limit counts from. */
        final long stored;
        /** The child's own messages: the envelope id reused on every send attempt, so a retry is a duplicate. */
        final String envelopeId;
        /** The child's own messages: whether the hub has accepted it. Parents' messages are always true. */
        boolean sent;

        Msg(long seq, String cid, String from, String text, long at, long stored, String envelopeId, boolean sent) {
            this.seq = seq;
            this.cid = cid;
            this.from = from;
            this.text = text;
            this.at = at;
            this.stored = stored;
            this.envelopeId = envelopeId;
            this.sent = sent;
        }

        boolean mine() {
            return PHONE.equals(from);
        }
    }

    /** The thread, oldest first (by {@code seq}). */
    final List<Msg> msgs = new ArrayList<>();
    /** The highest {@code seq} ever given out, so numbers keep counting up after pruning. */
    long lastSeq;
    /** The highest {@code seq} the child has seen. */
    long read;

    /** What {@link #add} did: the message as stored, and whether it is new (false for a repeated {@code cid}). */
    static final class Added {
        final Msg msg;
        final boolean isNew;

        Added(Msg msg, boolean isNew) {
            this.msg = msg;
            this.isNew = isNew;
        }
    }

    /**
     * Stores a message unless one with the same {@code cid} is already in the thread, in which case that one is
     * returned (a parent app's resend, or a replay). A new message gets the next {@code seq}.
     *
     * @param stored this phone's clock now
     * @param envelopeId for the child's own messages, the id every send attempt reuses; null for parents'
     */
    Added add(String cid, String from, String text, long at, long stored, String envelopeId) {
        Msg old = find(cid);
        if (old != null) return new Added(old, false);
        Msg m = new Msg(++lastSeq, cid, from, text, at, stored, envelopeId, envelopeId == null);
        msgs.add(m);
        return new Added(m, true);
    }

    /** The stored message with this {@code cid}, or null. */
    Msg find(String cid) {
        for (Msg m : msgs) if (m.cid.equals(cid)) return m;
        return null;
    }

    /** The newest message's {@code seq}, 0 when the thread is empty. */
    long newestSeq() {
        return msgs.isEmpty() ? 0 : msgs.get(msgs.size() - 1).seq;
    }

    /** Drops messages stored more than 90 days ago, then the oldest beyond 2,000. */
    void prune(long now) {
        msgs.removeIf(m -> now - m.stored > KEEP_MS);
        while (msgs.size() > MAX_MESSAGES) msgs.remove(0);
    }

    /** Up to {@link #PAGE} messages after {@code after}, oldest first: one {@code chatlog} page. */
    List<Msg> page(long after) {
        List<Msg> out = new ArrayList<>();
        for (Msg m : msgs) {
            if (m.seq <= after) continue;
            if (out.size() == PAGE) break;
            out.add(m);
        }
        return out;
    }

    /** Whether messages remain after the page that ends at {@code lastShown}. */
    boolean hasMoreAfter(long lastShown) {
        return newestSeq() > lastShown;
    }

    /** The child's own messages the hub hasn't accepted yet, oldest first. */
    List<Msg> unsent() {
        List<Msg> out = new ArrayList<>();
        for (Msg m : msgs) if (!m.sent) out.add(m);
        return out;
    }

    /** How many of the child's messages have gone out that the phone stored at or after {@code since} (ms). */
    int childSentSince(long since) {
        int n = 0;
        for (Msg m : msgs) if (m.mine() && m.sent && m.stored >= since) n++;
        return n;
    }

    // ---------- rules ----------

    /** The usual spacing between the child's chat sends. */
    static final long PACE_MS = 3_000L;
    /** The spacing once the child has sent {@link #HEAVY_DAY} messages in a day. */
    static final long PACE_HEAVY_MS = 60_000L;
    /** Messages in 24 hours after which sends slow to {@link #PACE_HEAVY_MS}. */
    static final int HEAVY_DAY = 500;

    /**
     * How long to wait before sending the next of the child's messages (0 = now). This is the flood guard: the
     * hub limits each household's address (a burst of about 120 requests, then one every 2 seconds, and 5,000
     * messages a day), and SOS alerts, location and the parents' commands share those limits. Spacing chat
     * sends keeps room for them. Nothing is refused: a waiting message is only sent later.
     *
     * @param lastSend when the previous chat message went out (ms, 0 if none since the app started)
     * @param sentToday the child's messages sent in the last 24 hours
     */
    static long paceWait(long lastSend, int sentToday, long now) {
        long gap = sentToday >= HEAVY_DAY ? PACE_HEAVY_MS : PACE_MS;
        if (lastSend <= 0 || now < lastSend) return 0;
        return Math.max(0, lastSend + gap - now);
    }

    /**
     * A message's text as stored: trimmed, null when nothing is left, else cut to
     * {@link #MAX_TEXT} characters. A parent's over-long message is cut rather than dropped, since a dropped
     * one would be resent for ever.
     */
    static String cleanText(String text) {
        if (text == null) return null;
        // Leading and trailing spaces and line breaks (a stray Enter before Send) are never part of the message.
        text = text.strip();
        if (text.isEmpty()) return null;
        if (text.codePointCount(0, text.length()) <= MAX_TEXT) return text;
        return text.substring(0, text.offsetByCodePoints(0, MAX_TEXT));
    }

    /** Whether {@code every} is one of slow mode's choices (0, 1, 5 or 15 minutes). */
    static boolean validEvery(int every) {
        for (int e : EVERY_CHOICES) if (e == every) return true;
        return false;
    }

    /** Why the child can't send now, and until when. */
    static final class Wait {
        /** When sending is possible again (ms). */
        final long until;
        /** True for a parent's timeout, false for slow mode. */
        final boolean timeout;

        Wait(long until, boolean timeout) {
            this.until = until;
            this.timeout = timeout;
        }
    }

    /**
     * Whether the child may send now, given the parents' settings.
     *
     * @param lastSent when the child last sent a message (this phone's clock; 0 for never). A time in the future
     *     (the clock was set back) counts as now, so it can't block sending for long.
     * @param every slow mode, minutes between messages (0 = off)
     * @param until timeout end in ms (0 or past = no timeout)
     * @return null when the child may send now; otherwise the later of the two limits, which is the one that decides
     *     when the child can send again
     */
    static Wait sendWait(long lastSent, int every, long until, long now) {
        long slowEnd = 0;
        if (every > 0 && lastSent > 0) slowEnd = Math.min(lastSent, now) + every * 60_000L;
        boolean slowActive = slowEnd > now;
        boolean timeoutActive = until > now;
        if (!slowActive && !timeoutActive) return null;
        if (timeoutActive && (!slowActive || until >= slowEnd)) return new Wait(until, true);
        return new Wait(slowEnd, false);
    }

    /**
     * How long to wait before sending the child's "seen" mark, so it goes at most once a minute: 0 to send now.
     *
     * @param lastSentAt when the last mark was sent (0 for never); a time in the future counts as never
     */
    static long readDelay(long lastSentAt, long now) {
        if (lastSentAt <= 0 || lastSentAt > now) return 0;
        return Math.max(0, lastSentAt + READ_EVERY_MS - now);
    }
}
