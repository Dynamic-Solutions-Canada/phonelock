package home.phonelock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** The family chat's thread on the child's phone: numbering, duplicates, retention, paging and send limits. */
public class ChatHistoryTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

    @Test
    public void messagesAreNumberedInTheOrderStored() {
        ChatHistory h = new ChatHistory();
        assertEquals(1, h.add("a", "Mom", "hi", NOW - 5000, NOW, null).msg.seq);
        assertEquals(2, h.add("b", "phone", "hey", NOW, NOW, "e1").msg.seq);
        // The sender's clock doesn't decide the order.
        assertEquals(3, h.add("c", "Dad", "late clock", NOW - 3600_000L, NOW, null).msg.seq);
        assertEquals(3, h.newestSeq());
    }

    @Test
    public void aResentCidIsStoredOnce() {
        ChatHistory h = new ChatHistory();
        ChatHistory.Added first = h.add("a", "Mom", "hi", NOW, NOW, null);
        ChatHistory.Added again = h.add("a", "Mom", "hi", NOW, NOW + MIN, null);
        assertTrue(first.isNew);
        assertFalse(again.isNew);
        assertSame(first.msg, again.msg);
        assertEquals(1, h.msgs.size());
        assertEquals(1, h.lastSeq);
    }

    @Test
    public void numbersKeepCountingAfterPruning() {
        ChatHistory h = new ChatHistory();
        h.add("old", "Mom", "hi", 0, NOW - ChatHistory.KEEP_MS - 1, null);
        h.prune(NOW);
        assertTrue(h.msgs.isEmpty());
        assertEquals(2, h.add("new", "Mom", "hi", NOW, NOW, null).msg.seq);
    }

    @Test
    public void pruningKeepsNinetyDaysAndTheNewestTwoThousand() {
        ChatHistory h = new ChatHistory();
        h.add("expired", "Mom", "x", 0, NOW - ChatHistory.KEEP_MS - 1, null);
        h.add("edge", "Mom", "x", 0, NOW - ChatHistory.KEEP_MS, null);
        h.prune(NOW);
        assertEquals(1, h.msgs.size());
        assertEquals("edge", h.msgs.get(0).cid);

        ChatHistory full = new ChatHistory();
        for (int i = 0; i < ChatHistory.MAX_MESSAGES + 5; i++) full.add("m" + i, "Mom", "x", NOW, NOW, null);
        full.prune(NOW);
        assertEquals(ChatHistory.MAX_MESSAGES, full.msgs.size());
        assertEquals(6, full.msgs.get(0).seq);
    }

    @Test
    public void historyIsPagedAfterAParentsNewestSeq() {
        ChatHistory h = new ChatHistory();
        for (int i = 0; i < 450; i++) h.add("m" + i, "Mom", "x", NOW, NOW, null);
        List<ChatHistory.Msg> first = h.page(0);
        assertEquals(ChatHistory.PAGE, first.size());
        assertEquals(1, first.get(0).seq);
        assertTrue(h.hasMoreAfter(first.get(first.size() - 1).seq));

        List<ChatHistory.Msg> last = h.page(400);
        assertEquals(50, last.size());
        assertEquals(401, last.get(0).seq);
        assertFalse(h.hasMoreAfter(450));
        assertTrue(h.page(450).isEmpty());
    }

    @Test
    public void onlyTheChildsUnsentMessagesWaitToSend() {
        ChatHistory h = new ChatHistory();
        h.add("p", "Mom", "hi", NOW, NOW, null);
        ChatHistory.Msg mine = h.add("k", "phone", "hey", NOW, NOW, "env").msg;
        assertTrue(mine.mine());
        assertEquals(1, h.unsent().size());
        mine.sent = true;
        assertTrue(h.unsent().isEmpty());
    }

    @Test
    public void textIsCheckedAndCut() {
        assertNull(ChatHistory.cleanText(null));
        assertNull(ChatHistory.cleanText("   "));
        assertEquals("hi", ChatHistory.cleanText("hi"));
        String max = "a".repeat(ChatHistory.MAX_TEXT);
        assertEquals(max, ChatHistory.cleanText(max));
        assertEquals(max, ChatHistory.cleanText(max + "b"));
        // An emoji is one character, and is never cut in half.
        String emoji = "😀".repeat(ChatHistory.MAX_TEXT + 1);
        String cut = ChatHistory.cleanText(emoji);
        assertEquals(ChatHistory.MAX_TEXT, cut.codePointCount(0, cut.length()));
    }

    @Test
    public void slowModeChoicesAreFixed() {
        for (int e : new int[] {0, 1, 5, 15}) assertTrue(ChatHistory.validEvery(e));
        for (int e : new int[] {-1, 2, 10, 60}) assertFalse(ChatHistory.validEvery(e));
    }

    @Test
    public void withNoLimitsTheChildMaySend() {
        assertNull(ChatHistory.sendWait(0, 0, 0, NOW));
        assertNull(ChatHistory.sendWait(NOW - 1000, 0, 0, NOW));
        assertNull(ChatHistory.sendWait(0, 5, 0, NOW)); // never sent: slow mode doesn't block the first one
        assertNull(ChatHistory.sendWait(NOW, 0, NOW - 1, NOW)); // a timeout that has ended
    }

    @Test
    public void slowModeWaitsFromTheLastMessage() {
        ChatHistory.Wait w = ChatHistory.sendWait(NOW - 2 * MIN, 5, 0, NOW);
        assertFalse(w.timeout);
        assertEquals(NOW + 3 * MIN, w.until);
        assertNull(ChatHistory.sendWait(NOW - 5 * MIN, 5, 0, NOW));
    }

    @Test
    public void aLastSendInTheFutureCountsAsNow() {
        ChatHistory.Wait w = ChatHistory.sendWait(NOW + 3600_000L, 1, 0, NOW);
        assertEquals(NOW + MIN, w.until);
    }

    @Test
    public void theLaterLimitDecides() {
        ChatHistory.Wait timeout = ChatHistory.sendWait(NOW, 1, NOW + 3600_000L, NOW);
        assertTrue(timeout.timeout);
        assertEquals(NOW + 3600_000L, timeout.until);

        // Slow mode outlasts a timeout that is about to end.
        ChatHistory.Wait slow = ChatHistory.sendWait(NOW, 15, NOW + MIN, NOW);
        assertFalse(slow.timeout);
        assertEquals(NOW + 15 * MIN, slow.until);
    }

    @Test
    public void theSeenMarkGoesAtMostOnceAMinute() {
        assertEquals(0, ChatHistory.readDelay(0, NOW));
        assertEquals(0, ChatHistory.readDelay(NOW - MIN, NOW));
        assertEquals(40_000, ChatHistory.readDelay(NOW - 20_000, NOW));
        assertEquals(0, ChatHistory.readDelay(NOW + 5000, NOW)); // clock set back
    }

    @Test
    public void spacesAndLineBreaksAroundAMessageAreDropped() {
        assertEquals("OK thanks", ChatHistory.cleanText("  OK thanks\n"));
    }

    @Test
    public void theChildsSendsAreSpacedAndSlowAfterABusyDay() {
        long now = 1_800_000_000_000L;
        assertEquals(0, ChatHistory.paceWait(0, 0, now));
        assertEquals(ChatHistory.PACE_MS - 1_000, ChatHistory.paceWait(now - 1_000, 10, now));
        assertEquals(0, ChatHistory.paceWait(now - ChatHistory.PACE_MS, 10, now));
        assertEquals(ChatHistory.PACE_HEAVY_MS - 1_000, ChatHistory.paceWait(now - 1_000, ChatHistory.HEAVY_DAY, now));
        // A clock set back doesn't hold messages up.
        assertEquals(0, ChatHistory.paceWait(now + 60_000, 10, now));
    }
}
