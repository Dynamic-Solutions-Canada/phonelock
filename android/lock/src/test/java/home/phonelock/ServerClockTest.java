package home.phonelock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The hub's clock, learned from the time ntfy gives a message it accepted. */
public class ServerClockTest {
    private static final long PHONE = 1_800_000_000_000L;

    @Test
    public void aPhoneClockAnHourSlowIsCorrected() {
        long serverSeconds = (PHONE + 3_600_000L) / 1000;
        Long offset = ServerClock.offsetFrom(PHONE - 100, PHONE + 100, serverSeconds);
        // Within the second ntfy rounds to.
        assertEquals(3_600_000L, offset, 1_000L);
    }

    @Test
    public void aSlowOrMissingAnswerTeachesNothing() {
        assertNull(ServerClock.offsetFrom(PHONE, PHONE + 30_000L, PHONE / 1000));
        assertNull(ServerClock.offsetFrom(PHONE, PHONE + 100, 0));
        assertNull(ServerClock.offsetFrom(PHONE, PHONE - 1, PHONE / 1000));
    }
}
